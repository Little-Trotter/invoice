package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.server.streams.DownloadHandler
import com.vaadin.flow.server.streams.DownloadResponse
import de.littletrotter.application.dto.PrintJobOverviewRow
import de.littletrotter.application.invoice.KositReportReader
import de.littletrotter.application.invoice.port.inbound.GenerationJobView
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.GermanTime
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.blankToNull
import de.littletrotter.ui.format.DateFormatter
import java.io.ByteArrayInputStream
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import de.littletrotter.ui.component.ActionIcons

// Filename/formatting helpers for InvoiceExportPanel's job grid, moved out of that file to
// keep it under the decomposition's line-count target. Pure functions, no UI-layer XML parsing.

/**
 * Derives a job/print-row download filename: the invoice number with slashes
 * replaced (rejected if blank), wrapped in [prefix]/[extension], or `<prefix>job-<jobId>.
 * <extension>` when there is no usable invoice number. [prefix] and [extension] carry every
 * per-call-site difference (e.g. `"zugferd-"`/`"xml"` vs `""`/`"pdf"`) so this helper never picks
 * a fallback text of its own.
 */
private fun deriveJobFileName(invoiceNumber: String?, jobId: Long?, prefix: String, extension: String): String {
    val sanitized = invoiceNumber?.replace("/", "-").blankToNull()
    return "$prefix${sanitized ?: "job-$jobId"}.$extension"
}

internal fun kositXmlFileName(job: GenerationJobView): String =
    deriveJobFileName(job.invoiceNumber, job.id, "kosit-", "xml")

internal fun zugferdXmlFileName(job: GenerationJobView): String =
    deriveJobFileName(job.invoiceNumber, job.id, "zugferd-", "xml")

private const val ATTR_DOWNLOAD = "download"
private const val ATTR_TITLE = "title"

    internal fun buildDateTimeCell(timestamp: OffsetDateTime?): Div = Div().apply {
        if (timestamp == null) {
            add(Span("—").apply { addClassName("lt-text-secondary") })
            return@apply
        }
        val zone = GermanTime.ZONE
        val dateLine = Span(DateFormatter.format(timestamp.atZoneSameInstant(zone).toLocalDate())).apply { addClassName("lt-job-date") }
        // Time-only ("HH:mm") is deliberately not routed through DateFormatter — it has no time-only
        // sibling, this is a compact two-line cell (the date line above already carries the full
        // date), and formatJobTimeRange below needs the exact same HH:mm formatter for its own,
        // differently-shaped "12:00 → 12:05" cell.
        val timeLine = Span(DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(timestamp)).apply { addClassName("lt-job-time") }
        addClassName("lt-job-datetime")
        add(dateLine, timeLine)
    }

/**
 * The print-job grid's single "Zeit" cell: `12:00 → 12:05` plus a secondary duration
 * (`5 min`). Job rows sit under a day group row that already names the calendar date, so the
 * created timestamp renders time-only; the finished timestamp gets a `dd.MM.` prefix only when
 * it falls on a different calendar day (an over-midnight job). A still-running job renders
 * `12:00 → —` with no duration.
 */
internal fun buildTimeRangeCell(createdAt: OffsetDateTime, finishedAt: OffsetDateTime?): Component =
    HorizontalLayout().apply {
        isPadding = false
        isSpacing = false
        defaultVerticalComponentAlignment = FlexComponent.Alignment.BASELINE
        addClassName("lt-gap-040")
        add(Span(formatJobTimeRange(createdAt, finishedAt)))
        formatJobDuration(createdAt, finishedAt)?.let { duration ->
            add(Span(duration).apply { addClassName("lt-job-duration") })
        }
    }

internal fun formatJobTimeRange(createdAt: OffsetDateTime, finishedAt: OffsetDateTime?): String {
    val zone = GermanTime.ZONE
    val time = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
    val created = time.format(createdAt)
    if (finishedAt == null) return "$created → —"
    val sameDay = createdAt.atZoneSameInstant(zone).toLocalDate() == finishedAt.atZoneSameInstant(zone).toLocalDate()
    val finished = if (sameDay) time.format(finishedAt) else DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(zone).format(finishedAt)
    return "$created → $finished"
}

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L

/** `42 s` / `5 min` / `5 min 30 s` / `2 h 14 min`; `null` for an unfinished job or a negative
 *  span (clock skew between the two worker-reported timestamps must not render `-3 s`). */
internal fun formatJobDuration(createdAt: OffsetDateTime, finishedAt: OffsetDateTime?): String? {
    if (finishedAt == null) return null
    val totalSeconds = java.time.Duration.between(createdAt, finishedAt).seconds
    if (totalSeconds < 0) return null
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = totalSeconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return when {
        hours > 0 -> if (minutes > 0) "$hours h $minutes min" else "$hours h"
        minutes > 0 -> if (seconds > 0) "$minutes min $seconds s" else "$minutes min"
        else -> "$seconds s"
    }
}

internal fun parseGenerationJobObjectKey(resultRefs: String?): String? = resultRefs?.let { ref ->
    resultRefPattern("objectKey").find(ref)?.groupValues?.get(1)
}

internal fun parseGenerationJobResultRef(resultRefs: String?, vararg keys: String): String? {
    if (resultRefs.isNullOrBlank()) return null
    return keys.firstNotNullOfOrNull { key ->
        resultRefPattern(key).find(resultRefs)?.groupValues?.get(1)
    }
}

internal fun getDownloadResourceName(job: GenerationJobView): String =
    deriveJobFileName(job.invoiceNumber, job.id, "", "pdf")

// ── Exact e-invoice format/version per artifact row ─────────────────────
// The UI must never parse XML itself; the worker already extracted the guideline URN into
// resultRefs["formatGuideline"] at export time (JobConsumer.publishCompletedResult). This is the
// one place kotlin-invoice turns that URN into a human label.

private val FACTUR_X_PROFILE_LABELS = mapOf(
    "minimum" to "MINIMUM",
    "basicwl" to "BASIC WL",
    "basic" to "BASIC",
    "en16931" to "EN 16931",
    "extended" to "EXTENDED",
)
/** Compiled once per key: the extraction runs per job row in lists, and the key set is a handful
 *  of constants, so the cache stays tiny. */
private val RESULT_REF_PATTERNS = java.util.concurrent.ConcurrentHashMap<String, Regex>()

private fun resultRefPattern(key: String): Regex =
    RESULT_REF_PATTERNS.computeIfAbsent(key) { Regex("\"${Regex.escape(it)}\"\\s*:\\s*\"([^\"]+)\"") }

private val URN_VERSION_TOKEN = Regex("""^\d+p\d+$""")
private val FACTUR_X_URN = Regex("""^urn:factur-x\.eu:(\d+p\d+):(\w+)$""")
private val ZUGFERD_URN = Regex("""^urn:zugferd\.de:(\d+p\d+):(\w+)$""")
private val XEINKAUF_XRECHNUNG_URN = Regex("""^urn:xeinkauf\.de:kosit:xrechnung_(\d+(?:\.\d+)?)$""")
private val XOEV_XRECHNUNG_URN = Regex("""^urn:xoev-de:kosit:standard:xrechnung_(\d+(?:\.\d+)?)$""")
private const val EN16931_URN = "urn:cen.eu:en16931:2017"

/** `1p0` -> `1.0`, `2p1` -> `2.1` — the version segment factur-x.eu/zugferd.de URNs encode. */
private fun derivePointVersion(token: String): String =
    if (URN_VERSION_TOKEN.matches(token)) token.replace('p', '.') else token

/**
 * Maps the guideline URN (`rsm:ExchangedDocumentContext/
 * ram:GuidelineSpecifiedDocumentContextParameter/ram:ID`) to a human label, e.g.
 * "Factur-X 1.0 / ZUGFeRD 2 · EN 16931" or "XRechnung 3.0". Composed URNs
 * (`urn:cen.eu:en16931:2017#compliant#<suffix>`, or `#conformant#` for EXTENDED) are matched on
 * their suffix. An unknown URN is returned verbatim, nothing is labeled
 * "unbekannt" here — `null` only for a `null`/blank input.
 */
fun formatDescriptorLabel(guidelineUrn: String?): String? {
    val trimmed = guidelineUrn?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val suffix = trimmed.substringAfterLast('#')
    return facturXLabel(suffix)
        ?: zugferdLabel(suffix)
        ?: xeinkaufXRechnungLabel(suffix)
        ?: xoevXRechnungLabel(suffix)
        ?: en16931Label(suffix)
        ?: trimmed
}

private fun facturXLabel(suffix: String): String? {
    val (versionToken, profile) = FACTUR_X_URN.matchEntire(suffix)?.destructured ?: return null
    val profileLabel = FACTUR_X_PROFILE_LABELS[profile.lowercase()] ?: profile.uppercase()
    return "Factur-X ${derivePointVersion(versionToken)} / ZUGFeRD 2 · $profileLabel"
}

private fun zugferdLabel(suffix: String): String? {
    val (versionToken, profile) = ZUGFERD_URN.matchEntire(suffix)?.destructured ?: return null
    return "ZUGFeRD ${derivePointVersion(versionToken)} · ${profile.uppercase()}"
}

private fun xeinkaufXRechnungLabel(suffix: String): String? =
    XEINKAUF_XRECHNUNG_URN.matchEntire(suffix)?.let { "XRechnung ${it.groupValues[1]}" }

private fun xoevXRechnungLabel(suffix: String): String? =
    XOEV_XRECHNUNG_URN.matchEntire(suffix)?.let { "XRechnung ${it.groupValues[1]}" }

private fun en16931Label(suffix: String): String? = if (suffix == EN16931_URN) "EN 16931" else null

/** `formatGuideline` (worker-extracted URN) first, falling back to the KoSIT
 *  payload's own `profile` field for a legacy job that predates this, `null` when neither exists
 *  (the caller then renders nothing extra). */
internal fun resolveFormatLabel(job: GenerationJobView): String? {
    val fromGuideline = formatDescriptorLabel(parseGenerationJobResultRef(job.resultRefs, "formatGuideline"))
    if (fromGuideline != null) return fromGuideline
    return KositReportReader.read(job.kositReport).profile
}

/** The primary, inline-viewed PDF artifact — see [buildPdfAnchor]. `jobId` is `null` only for a
 *  job that was never persisted (not reachable through either [buildDownloadAction] overload in
 *  real use); guarded defensively so a stable route is never built with a missing id segment. */
private data class PdfArtifact(val jobId: Long?, val objectKey: String?)

/** The e-invoice XML artifact. [checkExists] is `true` for the entity overload of
 *  [buildDownloadAction] (a small per-invoice grid can afford the extra [DocumentStore.exists]
 *  round trip at render time) and `false` for the row overload (spec: no store round trip
 *  while a paged grid renders, only on click). See [buildXmlAnchor]. */
private data class XmlArtifact(val jobId: Long?, val objectKey: String?, val buttonLabel: String, val checkExists: Boolean)

/** The signed archive package. `null` [objectKey] renders no third button at all (a draft
 *  job, a legacy job that predates the archive package, or the row overload, which does not carry resultRefs at all — see
 *  [buildDownloadAction]'s two overloads' own KDoc). See [buildArchiveAnchor]. */
private data class ArchiveArtifact(val jobId: Long? = null, val objectKey: String? = null)

/** The two collaborators every [buildDownloadActionCore] call shares (only the three artifacts and
 *  the completed flag ever vary per call) — bundled purely to keep that function's parameter count
 *  under detekt's `LongParameterList` threshold. */
private class DownloadRenderContext(val documentStore: DocumentStore, val tr: (String) -> String)

/** A job's download row is one real concept (kept off `LongParameterList`): its PDF/XML/archive
 *  artifacts, whether the job finished, and the format-guideline label underneath. The two
 *  [buildDownloadAction] overloads each assemble one of these from their own source (the generation-job read model
 *  or [PrintJobOverviewRow]); [buildDownloadActionCore] renders it. Kept separate from
 *  [DownloadRenderContext], which carries the rendering *dependencies* (store, tr) rather than the
 *  job's own data. */
private data class DownloadArtifacts(
    val completed: Boolean,
    val pdf: PdfArtifact,
    val xml: XmlArtifact,
    val archive: ArchiveArtifact,
    val formatLabel: String?,
)

/**
 * Every download button now links to the stable, authenticated JAX-RS route
 * [de.littletrotter.ui.documents.DocumentDownloadResource] serves (`GET /documents/jobs/{jobId}
 * /pdf|xml|archive`) instead of a per-cell Vaadin `DownloadHandler`: a `DownloadHandler`'s
 * `/VAADIN/dynamic/resource/<uiId>/<uuid>/` URL only lives while the owning grid cell stays
 * attached, so a live-update `refreshAll()` (push or poll, [PrintJobListView]/[InvoiceExportPanel])
 * killed every URL the browser already held on every refresh — the root cause this task fixes.
 * `Anchor(String)` is a plain `<a href>`, no per-render registration at all, so the URL a cell
 * renders today is byte-identical to the one it renders after any number of refreshes. Bytes are
 * fetched, checksum-verified and streamed by the resource itself, never in this module.
 *
 * H27/PDF-tab-fix — Chrome honors the anchor `download` attribute strictly (silent save, no tab);
 * Firefox's viewer tab was the reference behavior. Matching [ReceiptDialog]/[ReceiptPoolView]'s
 * established "view" pattern: `target="_blank"` and NO `download` attribute against the resource's
 * own `Content-Disposition: inline` response opens a viewer tab in both browsers. `null` when the
 * job isn't completed or carries no PDF key yet.
 */
private fun buildPdfAnchor(completed: Boolean, pdf: PdfArtifact): Anchor? {
    if (!completed || pdf.jobId == null || pdf.objectKey.isNullOrBlank()) return null
    return Anchor("documents/jobs/${pdf.jobId}/pdf", "").also { a ->
        a.element.setAttribute("target", "_blank")
        a.add(AppButtons.primary("PDF", ActionIcons.PDF) {}.apply { addThemeVariants(ButtonVariant.LUMO_SMALL); element.setAttribute(ATTR_TITLE, "PDF ansehen") })
    }
}

/** A plain attachment download — never meant to render in a tab, unlike [buildPdfAnchor]'s inline
 *  viewer. `null` when the job isn't completed, or [XmlArtifact.checkExists] is set and a
 *  [DocumentStore.exists] probe says the object isn't actually there. */
private fun buildXmlAnchor(completed: Boolean, xml: XmlArtifact, ctx: DownloadRenderContext): Anchor? {
    if (!completed || xml.jobId == null) return null
    val available = !xml.objectKey.isNullOrBlank() &&
        (!xml.checkExists || try { ctx.documentStore.exists(xml.objectKey) } catch (_: Exception) { false })
    if (!available) return null
    return Anchor("documents/jobs/${xml.jobId}/xml", "").also { a ->
        a.element.setAttribute(ATTR_DOWNLOAD, true)
        a.add(AppButtons.tertiary(xml.buttonLabel, ActionIcons.XML) {}.apply { addThemeVariants(ButtonVariant.LUMO_SMALL); element.setAttribute(ATTR_TITLE, ctx.tr("invoiceDetail.jobs.download.zugferdXml")) })
    }
}

/** `null` when the job isn't completed, or [ArchiveArtifact] carries no key (see its own
 *  KDoc for when that legitimately happens). */
private fun buildArchiveAnchor(completed: Boolean, archive: ArchiveArtifact, ctx: DownloadRenderContext): Anchor? {
    if (!completed || archive.jobId == null || archive.objectKey.isNullOrBlank()) return null
    return Anchor("documents/jobs/${archive.jobId}/archive", "").also { a ->
        a.element.setAttribute(ATTR_DOWNLOAD, true)
        a.add(
            AppButtons.tertiary("TAR.GZ", ActionIcons.ARCHIVE) {}.apply {
                addThemeVariants(ButtonVariant.LUMO_SMALL)
                element.setAttribute(ATTR_TITLE, ctx.tr("invoiceDetail.jobs.download.archive"))
            },
        )
    }
}

/**
 * The shared core of the download action cell, factored out of the former
 * `buildDownloadAction(job, ...)` body so both [InvoiceExportPanel]'s per-job entity grid and
 * [de.littletrotter.ui.view.invoice.PrintJobListView]'s flat-row grid render the identical PDF/XML
 * button pair from their own, differently-shaped data (the generation-job read model vs. a flat
 * [PrintJobOverviewRow]). Each artifact's own branching (is it completed? present? checked against
 * the store?) lives in its own [buildPdfAnchor]/[buildXmlAnchor]/[buildArchiveAnchor] helper —
 * this function only assembles whatever they return.
 */
private fun buildDownloadActionCore(artifacts: DownloadArtifacts, ctx: DownloadRenderContext): Component {
    val row = HorizontalLayout().apply {
        isSpacing = false
        defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
        addClassName("lt-gap-040")
    }
    listOfNotNull(
        buildPdfAnchor(artifacts.completed, artifacts.pdf),
        buildXmlAnchor(artifacts.completed, artifacts.xml, ctx),
        buildArchiveAnchor(artifacts.completed, artifacts.archive, ctx),
    ).forEach(row::add)
    // PO decision (2026-08-25): the download row offers only the two artifacts of the job
    // itself — PDF and the e-invoice XML. The KoSIT report is reachable through the validation
    // report dialog, which also carries its download; a third "Bericht" button here duplicated
    // that and crowded the row.
    if (row.componentCount == 0) return Span("—").apply { addClassName("lt-text-secondary") }
    val formatLabel = artifacts.formatLabel ?: return row
    return VerticalLayout(
        row,
        Span(formatLabel).apply { addClassName("lt-job-format") },
    ).apply { isPadding = false; isSpacing = false; setWidthFull() }
}

/** The grid's "download" cell, moved out of `InvoiceExportPanel.createDownloadAction` (same
 *  content, now a top-level function taking its dependencies as parameters). */
internal fun buildDownloadAction(
    job: GenerationJobView,
    documentStore: DocumentStore,
    tr: (String) -> String,
): Component {
    val formatLabel = resolveFormatLabel(job)
    val objectKey = parseGenerationJobObjectKey(job.resultRefs)
    // "xmlObjectKey"/"xrechnungObjectKey"/"xml" were dead fallback key names (kotlin-xpdf's
    // JobConsumer.publishCompletedResult, the only producer, never wrote any of them); removed as
    // dead code. Coordinator review (2026-09-11): the .pdf -> .xml derivation (deriveZugferdObjectKey,
    // now deleted) is ALSO removed, not kept — an empty zugferdObjectKey is exactly the rejected-
    // Factur-X-engine outcome, which has no XML at all; deriving one from the PDF key would have
    // been wrong, not merely reachable. zugferdObjectKey is the only source: absent/empty means no
    // XML button, full stop.
    val zugferdObjectKey = parseGenerationJobResultRef(job.resultRefs, "zugferdObjectKey")
    // Once the format line below already names the exact standard, "ZUGFeRD XML" is
    // redundant noise; keep the fuller label only when there is nothing else saying what this
    // artifact actually is.
    val zugferdButtonLabel = if (formatLabel != null) "XML" else "ZUGFeRD XML"
    // Absent for a draft job (no archive package is ever built for one, see kotlin-xpdf's
    // PdfExportServiceImpl.buildAndStoreArchivePackage) or a legacy job that predates the archive package.
    val archiveObjectKey = parseGenerationJobResultRef(job.resultRefs, "archiveObjectKey")
    return buildDownloadActionCore(
        artifacts = DownloadArtifacts(
            completed = job.status == GenerationJobStatus.COMPLETED,
            pdf = PdfArtifact(job.id, objectKey),
            xml = XmlArtifact(job.id, zugferdObjectKey, zugferdButtonLabel, checkExists = true),
            archive = ArchiveArtifact(job.id, archiveObjectKey),
            formatLabel = formatLabel,
        ),
        ctx = DownloadRenderContext(documentStore, tr),
    )
}

/** The archive package's own download filename, same `deriveJobFileName` idiom as
 *  [zugferdXmlFileName]/[getDownloadResourceName]. Still resolved here (not just in
 *  [de.littletrotter.ui.documents.DocumentDownloadResource]) because [de.littletrotter.ui.view
 *  .invoice.report.KositReportDialog]-adjacent callers and this file's own tests pin the naming
 *  rule directly; the resource reuses this exact function (same module, `internal` visibility) so
 *  the two never drift. */
internal fun archiveFileName(job: GenerationJobView): String =
    deriveJobFileName(job.invoiceNumber, job.id, "", "tar.gz")

/**
 * The row overload: same rendering as [buildDownloadAction] above, but reading the
 * already-extracted [PrintJobOverviewRow.pdfObjectKey]/[PrintJobOverviewRow.xmlObjectKey] fields
 * instead of a full generation-job read model — the global print-jobs grid
 * ([de.littletrotter.ui.view.invoice.PrintJobListView]) never loads one per row (that was the
 * whole point of its flat projection). No format-guideline label line underneath (that needs
 * the job's `resultRefs`'s `formatGuideline` entry or the job's `kositReport`'s own
 * profile field, neither of which the flat row carries — this view keeps its own
 * "lightweight fields only" constraint): a deliberate simplification versus the per-invoice grid,
 * not a bug. The anchor links by [PrintJobOverviewRow.jobId] alone; the actual filename is
 * resolved server-side, from the full entity, by [de.littletrotter.ui.documents
 * .DocumentDownloadResource].
 */
internal fun buildDownloadAction(
    row: PrintJobOverviewRow,
    documentStore: DocumentStore,
    tr: (String) -> String,
): Component =
    buildDownloadActionCore(
        artifacts = DownloadArtifacts(
            completed = row.status == GenerationJobStatus.COMPLETED,
            pdf = PdfArtifact(row.jobId, row.pdfObjectKey),
            xml = XmlArtifact(row.jobId, row.xmlObjectKey, "XML", checkExists = false),
            archive = ArchiveArtifact(),
            formatLabel = null,
        ),
        ctx = DownloadRenderContext(documentStore, tr),
    )

/**
 * Shared "download the raw KoSIT report XML" footer anchor for
 * [de.littletrotter.ui.view.invoice.report.KositReportDialog] and
 * [de.littletrotter.ui.view.invoice.report.ValidationReportDialog], which both offer the exact
 * same in-memory-string download button for their raw KoSIT XML.
 */
internal fun rawXmlDownloadAnchor(xmlFileName: String, rawXml: String, label: String): Anchor {
    val handler = DownloadHandler.fromInputStream {
        val data = rawXml.toByteArray(Charsets.UTF_8)
        DownloadResponse(ByteArrayInputStream(data), xmlFileName, "application/xml", data.size.toLong())
    }
    return Anchor(handler, "").also { a ->
        a.element.setAttribute(ATTR_DOWNLOAD, true)
        a.add(AppButtons.tertiary(label, ActionIcons.DOWNLOAD) {})
    }
}
