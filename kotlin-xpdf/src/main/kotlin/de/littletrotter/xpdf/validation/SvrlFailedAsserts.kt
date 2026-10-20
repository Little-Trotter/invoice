package de.littletrotter.xpdf.validation

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

private const val SVRL_NS = "http://purl.oclc.org/dsdl/svrl"
private const val SEVERITY_ERROR = "error"

/**
 * One `svrl:failed-assert` element, attributes normalized (blank → `null`). Parsed from the
 * standard ISO-Schematron SVRL that [CiiSchematronValidator]'s Saxon transform produces. [test] is the original
 * Schematron assert expression the compiled stylesheet's skeleton copies into the report
 * verbatim; [CiiSchematronValidator] uses it for its code-list-violation marker.
 */
internal data class SvrlFailedAssert(
    val id: String?,
    val flag: String?,
    val location: String?,
    val test: String?,
    val text: String,
)

/** Parses `svrl:failed-assert` elements directly (plain DOM, no JAXB) — the SVRL both callers
 *  feed in is their own Saxon output, the same shape KoSIT's embedded engine emits internally,
 *  but neither caller is a KoSIT scenario and has no reason to depend on `org.kosit`'s internal
 *  SVRL JAXB model for a document it generates itself. */
internal fun parseSvrlFailedAsserts(svrl: String): List<SvrlFailedAssert> {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        // SVRL is our own Saxon output, not untrusted external input in the classic XXE sense,
        // but there's no reason to resolve external entities/DTDs to parse it either.
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }
    val document = factory.newDocumentBuilder().parse(InputSource(StringReader(svrl)))
    val nodes = document.getElementsByTagNameNS(SVRL_NS, "failed-assert")
    return buildList {
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            val textNodes = el.getElementsByTagNameNS(SVRL_NS, "text")
            val text = if (textNodes.length > 0) textNodes.item(0).textContent.trim() else ""
            add(
                SvrlFailedAssert(
                    id = el.getAttribute("id").takeIf { it.isNotBlank() },
                    flag = el.getAttribute("flag").takeIf { it.isNotBlank() },
                    location = el.getAttribute("location").takeIf { it.isNotBlank() },
                    test = el.getAttribute("test").takeIf { it.isNotBlank() },
                    text = text,
                ),
            )
        }
    }
}

/** Mirrors de.littletrotter.xpdf.impl.EmbeddedKositValidator's own `mapSeverityFlag` fallback
 *  rule: an unrecognized or absent `flag` is treated as `error`, never silently dropped. Shared
 *  by both SVRL consumers in this package; `internal`, not `private`, so
 *  `CiiSeverityMappingTest` can exercise every branch directly — the real Schematron
 *  fixtures only ever produce an absent `flag` (mapped to `error` via the fallback), never
 *  `warning`/`information`/`fatal`, in any bundled assert we've mutated a fixture to reach. */
internal fun mapSeverityFlag(flag: String?): String = when (flag?.lowercase()) {
    "fatal", SEVERITY_ERROR -> SEVERITY_ERROR
    "warning" -> "warning"
    "information", "info" -> "info"
    else -> SEVERITY_ERROR
}
