package de.littletrotter.application.invoice

import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload

/** ERROR ("Prüfung fehlgeschlagen") is a technical failure of the validator itself — distinct
 *  from INVALID ("nicht konform", a real finding against the invoice) and from UNKNOWN ("kein
 *  Ergebnis", no report exists yet or it could not be parsed). PO decision:
 *  outwardly, a validation is binary — successful or not — and ERROR/UNKNOWN are two of several
 *  *reasons* "not successful" can have, on the same footing as an actual INVALID finding; none
 *  of the four values here gets its own user-facing status anymore (see
 *  [de.littletrotter.ui.view.invoice.resolveValidationOutcome], the single place that derives
 *  the outward result). This enum stays the parser's internal vocabulary — [KositReportReader.read] must never
 *  throw — and [KositStatus.UNKNOWN] in particular must never surface as-is in the UI. */
enum class KositStatus { VALID, INVALID, ERROR, UNKNOWN }

data class KositMessage(
    val position: String?,
    val code: String?,
    val severity: String,
    val text: String,
    val location: String?,
    // F3 — the stage this finding belongs to (SYNTAX/KOSIT/PDFA/BUSINESS); null on findings
    // from pre-F3 payloads, which the UI then renders flat as before.
    val stage: String? = null,
    // Message-format arguments for [text] when it is an i18n key (a BUSINESS-stage finding from
    // ComplianceFindingMapper) rather than already-resolved prose (KoSIT/veraPDF). Null for
    // legacy payloads and the XML/plain-text fallback parsers, which never carried this data —
    // the UI then renders [text] unchanged, same as an unresolvable key.
    val args: List<String>? = null,
)

/** F3 — one named validation stage as carried in the payload's ValidationRun; the UI groups
 *  findings by it and shows skipped stages with their reason instead of hiding them. */
data class KositStage(
    val name: String,
    val outcome: KositStatus,
    val findingCount: Int,
    val skipped: Boolean = false,
    val skipReason: String? = null,
    // Only ever set when outcome is ERROR: the human-readable reason a technical validator
    // failure occurred. Null for pre-F3-engine-error payloads and stages without the field.
    val engineError: String? = null,
    /** The stage's result has not arrived yet; [outcome] is a placeholder. */
    val pending: Boolean = false,
    /** Findings of this stage only warn and never decide the verdict (veraPDF). */
    val advisory: Boolean = false,
) {
    /** The state the report shows instead of a pass/fail wording, or `null` for an ordinary stage. */
    val stateLabel: String?
        get() = when {
            pending -> "ausstehend"
            advisory && outcome == KositStatus.ERROR -> "Prüfung nicht möglich"
            else -> null
        }
}

data class KositResult(
    val status: KositStatus,
    val errors: List<KositMessage>,
    val warnings: List<KositMessage>,
    val isXml: Boolean,
    // F2 — version block (CO-03/CO-06). Only ever populated from the JSON envelope; the
    // legacy XML/plain-text fallback parsers never had this data to begin with.
    val validatorVersion: String? = null,
    val generatorVersion: String? = null,
    val profile: String? = null,
    val checkedAt: String? = null,
    // F3 — the multi-stage breakdown; empty for pre-F3 payloads (the UI then renders flat).
    val stages: List<KositStage> = emptyList(),
    /** PO decision — the payload's own flat summary text, carried through only so
     *  [de.littletrotter.ui.view.invoice.resolveValidationOutcome] has a fallback ERROR detail
     *  when no stage names its own [KositStage.engineError]. Null for the legacy XML/plain-text
     *  fallback parsers below, which never had this data to begin with. */
    val payloadSummary: String? = null,
)

/**
 * The SAX [org.xml.sax.ErrorHandler] [parseXmlReport] installs, extracted to a named
 * top-level `object` (rather than an anonymous class inline in the method) so it is directly
 * unit-testable: `warning`/`error`/`fatalError` are all real, reachable overrides of a public
 * interface, not dead code — a non-validating [javax.xml.parsers.DocumentBuilder] (no XSD/DTD
 * attached here) only ever calls [fatalError] for a well-formedness violation in practice, but the
 * interface contract requires all three, and every override is independently callable. `internal`
 * so `KositReportReaderTest` can invoke it directly without going through a real SAX parse.
 */
internal object KositXmlErrorHandler : org.xml.sax.ErrorHandler {
    override fun warning(e: org.xml.sax.SAXParseException) = Unit
    override fun error(e: org.xml.sax.SAXParseException) = throw e
    override fun fatalError(e: org.xml.sax.SAXParseException) = throw e
}

/**
 * Z3 — interprets a KoSIT report payload into a small, immutable result model the UI can render
 * without ever touching XML parsing types itself. Previously this lived in the UI layer
 * (`InvoiceExportPanel`) as a set of top-level functions using `org.w3c.dom`/`org.xml.sax`
 * directly — a layer violation the ArchUnit suite now forbids.
 *
 * The payload comes in one of three shapes, tried in order:
 *  1. A JSON envelope ([KositReportPayload]) — the current format (Task: print-improve).
 *  2. A raw KoSIT validator XML report — the legacy format, parsed via SAX/DOM.
 *  3. Anything else — a best-effort plain-text fallback based on a few keywords.
 */
object KositReportReader {
    private val XML_TAG = Regex("<[^>]+>")
    private val WHITESPACE_RUN = Regex("\\s+")

    fun read(raw: String?): KositResult {
        if (raw.isNullOrBlank()) return KositResult(KositStatus.UNKNOWN, emptyList(), emptyList(), false)

        val payload = KositReportPayload.decode(raw)
        if (payload != null) {
            return parseJsonPayload(payload)
        }

        return try {
            parseXmlReport(raw)
        } catch (_: Exception) {
            parseFallback(raw)
        }
    }

    private fun parseJsonPayload(payload: KositReportPayload): KositResult {
        val messages = payload.details.map { detail ->
            KositMessage(
                position = null,
                code = detail.ruleId,
                severity = detail.severity.uppercase(),
                text = detail.message,
                location = detail.location,
                stage = detail.stage,
                args = detail.args,
            )
        }
        val errors = messages.filter { it.severity in setOf("ERROR", "FATAL") }
        val warnings = messages.filter { it.severity == "WARNING" }
        val status = when (payload.status) {
            "INVALID" -> KositStatus.INVALID
            "ERROR" -> KositStatus.ERROR
            "VALID" -> KositStatus.VALID
            else -> if (errors.isNotEmpty()) KositStatus.INVALID else KositStatus.VALID
        }
        val stages = payload.validationRun?.stages?.map { s ->
            KositStage(
                name = s.stage,
                outcome = when (s.outcome) {
                    KositOutcome.VALID -> KositStatus.VALID
                    KositOutcome.INVALID -> KositStatus.INVALID
                    else -> KositStatus.ERROR
                },
                findingCount = s.findings.size,
                skipped = s.skipped,
                skipReason = s.skipReason,
                engineError = s.engineError,
                pending = s.pending,
                advisory = s.advisory,
            )
        } ?: emptyList()
        return KositResult(
            status, errors, warnings, false,
            validatorVersion = payload.validatorVersion,
            generatorVersion = payload.generatorVersion,
            profile = payload.profile,
            checkedAt = payload.checkedAt,
            stages = stages,
            payloadSummary = payload.summary,
        )
    }

    private fun parseXmlReport(raw: String): KositResult {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().also { it.isNamespaceAware = true }
        val builder = factory.newDocumentBuilder().also { b -> b.setErrorHandler(KositXmlErrorHandler) }
        val doc = builder.parse(org.xml.sax.InputSource(java.io.StringReader(raw)))
        val allMessages: List<KositMessage> = walkAll(doc.documentElement, "message").mapNotNull { msg ->
            val text = firstIn(msg, "text") ?: msg.textContent?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val severity = (firstIn(msg, "type") ?: attrOrNull(msg, "level"))?.uppercase() ?: "UNKNOWN"
            val code = firstIn(msg, "code") ?: attrOrNull(msg, "code")
            val location = firstIn(msg, "xpathLocation", "location", "xpath") ?: attrOrNull(msg, "xpathLocation") ?: attrOrNull(msg, "location") ?: attrOrNull(msg, "xpath")
            val position = attrOrNull(msg, "id") ?: firstIn(msg, "position", "id")
            KositMessage(position, code, severity, text, location)
        }
        val errors = allMessages.filter { it.severity in setOf("ERROR", "FATAL") }
        val warnings = allMessages.filter { it.severity == "WARNING" }
        val status = resolveXmlStatus(doc.documentElement, errors, raw)
        return KositResult(status, errors, warnings, true)
    }

    private fun resolveXmlStatus(root: org.w3c.dom.Element, errors: List<KositMessage>, raw: String): KositStatus {
        val resultTexts = walkAll(root, "result").map { it.textContent.trim().uppercase() }
        val rootValidAttr = root.getAttribute("valid").trim().lowercase()
        val hasAccept = walkAll(root, "accept").isNotEmpty()
        val hasReject = walkAll(root, "reject").isNotEmpty()
        val upper = raw.uppercase()
        return when {
            rootValidAttr == "true" && errors.isEmpty() -> KositStatus.VALID
            rootValidAttr == "false" -> KositStatus.INVALID
            hasAccept && !hasReject && errors.isEmpty() -> KositStatus.VALID
            hasReject -> KositStatus.INVALID
            resultTexts.any { it in setOf("INVALID", "FAILED", "NOT_VALID") } || errors.isNotEmpty() -> KositStatus.INVALID
            resultTexts.any { it in setOf("VALID", "ACCEPTABLE") } && errors.isEmpty() -> KositStatus.VALID
            upper.contains("INVALID") || upper.contains("EXCEPTION") -> KositStatus.INVALID
            upper.contains("VALID") -> KositStatus.VALID
            else -> KositStatus.UNKNOWN
        }
    }

    private fun parseFallback(raw: String): KositResult {
        val stripped = stripXmlTags(raw)
        val messages = if (stripped.isNotBlank()) listOf(KositMessage(null, null, "ERROR", stripped, null)) else emptyList()
        val upper = raw.uppercase()
        val status = when {
            upper.contains("VALID") && !upper.contains("INVALID") -> KositStatus.VALID
            upper.contains("INVALID") || upper.contains("EXCEPTION") -> KositStatus.INVALID
            raw.trimStart().startsWith("<") -> KositStatus.UNKNOWN
            else -> KositStatus.INVALID
        }
        return KositResult(status, messages, emptyList(), false)
    }

    private fun localName(node: org.w3c.dom.Node): String =
        node.localName ?: node.nodeName.substringAfterLast(':')

    private fun walkAll(root: org.w3c.dom.Node, name: String): List<org.w3c.dom.Element> {
        val out = mutableListOf<org.w3c.dom.Element>()
        val stack = ArrayDeque<org.w3c.dom.Node>().also { it.add(root) }
        while (stack.isNotEmpty()) {
            val n = stack.removeFirst()
            if (n is org.w3c.dom.Element && localName(n).equals(name, ignoreCase = true)) out.add(n)
            val ch = n.childNodes
            for (i in 0 until ch.length) stack.add(ch.item(i))
        }
        return out
    }

    private fun walkIn(parent: org.w3c.dom.Element, name: String): List<org.w3c.dom.Element> = walkAll(parent, name)

    private fun firstIn(parent: org.w3c.dom.Element, vararg names: String): String? =
        names.firstNotNullOfOrNull { name -> walkIn(parent, name).firstOrNull()?.textContent?.trim()?.takeIf { it.isNotBlank() } }

    private fun attrOrNull(element: org.w3c.dom.Element, name: String): String? =
        element.getAttribute(name).trim().takeIf { it.isNotBlank() }

    private fun stripXmlTags(raw: String): String =
        raw.replace(XML_TAG, " ")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
            .replace(WHITESPACE_RUN, " ")
            .trim()
}
