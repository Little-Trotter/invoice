package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import de.littletrotter.application.dto.ModelReport
import de.littletrotter.application.invoice.port.inbound.GenerationJobView
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.shared.DocumentStore
import de.littletrotter.ui.component.ChipTone
import de.littletrotter.ui.component.GlyphAppearance
import de.littletrotter.ui.component.StatusGlyph
import de.littletrotter.ui.component.clickableStatusGlyph
import de.littletrotter.ui.component.blankToNull
import de.littletrotter.ui.view.invoice.report.AiReportDialog
import de.littletrotter.ui.view.invoice.report.ValidationReportDialog

// Job grid cell/action builders reusable beyond InvoiceExportPanel's own per-invoice grid:
// a future global print-jobs view (all GenerationJobs across all invoices, one row per job) needs
// exactly the same status/validation/AI/report cells for its own grid. Moved out of
// InvoiceExportPanel unchanged in behavior; that panel now calls these instead of keeping private
// copies. The download action's builder ([buildDownloadAction]) already lived top-level in
// GenerationJobFormatting.kt before this extraction and needed no move.
//
// [tr] is the `(key, args) -> String` shape already used for cross-file i18n closures in this
// package (see [buildValidationReportMarkdown]'s `resolveMessageKey`); a caller with a
// component-aware `tr(key, vararg params)` helper wires in with
// `{ key, args -> tr(key, *args.toTypedArray()) }`.

/**
 * The "Status" column's cell: the job's [buildJobStatusGlyph], whose tooltip also carries the
 * attempts count (once more than one attempt happened) and the last error. A job created while the
 * invoice was still DRAFT/IN_REVIEW (the job's `issued` flag `false`) gets the draft symbol next to
 * it: it never locks generation and its PDF carries the draft watermark. `issued` is fixed at
 * creation, so the symbol is correct even while the job is still running.
 */
internal fun buildJobStatusCell(job: GenerationJobView, tr: (String, List<Any>) -> String): Component =
    HorizontalLayout().apply {
        isPadding = false; isSpacing = true
        defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
        add(buildJobStatusGlyph(job.status, buildStatusTooltip(job.status, job.attempts, job.lastError, tr)))
        if (!job.issued) {
            add(buildDraftGlyph(tr))
        }
    }

/** The draft symbol [buildJobStatusCell] adds for a job whose the job's `issued` flag is `false`:
 *  neutral, because it informs rather than judges. Same pencil as the invoice list's draft status. */
internal fun buildDraftGlyph(tr: (String, List<Any>) -> String): StatusGlyph =
    StatusGlyph(VaadinIcon.EDIT, ChipTone.NEUTRAL, tr("invoiceDetail.jobs.draftChip.tooltip", emptyList()))

/** The job status as a symbol in its status colour; [label] is tooltip and accessible name and
 *  defaults to "Auftragsstatus: …". A running job pulses. */
internal fun buildJobStatusGlyph(status: GenerationJobStatus, label: String): StatusGlyph =
    StatusGlyph(generationJobStatusGlyphIcon(status), generationJobStatusTone(status), label, pulsing = status == GenerationJobStatus.RUNNING)

/**
 * The status tooltip: status name, attempts count (only past the first attempt) and last error
 * (when present), folded into one string so the cell itself stays a single symbol.
 */
internal fun buildStatusTooltip(
    status: GenerationJobStatus,
    attempts: Int,
    lastError: String?,
    tr: (String, List<Any>) -> String,
): String {
    val statusLabel = tr("invoiceDetail.jobs.status.${status.name.lowercase()}", emptyList())
    val clauses = buildList {
        add(tr("invoiceDetail.jobs.status.tooltip", listOf(statusLabel)))
        if (attempts > 1) add(tr("invoiceDetail.jobs.attempts", listOf(attempts)))
        lastError.blankToNull()?.let { add(tr("invoiceDetail.jobs.lastError.tooltip", listOf(it))) }
    }
    return clauses.joinToString(" — ")
}

/**
 * The "Validierung" column's cell: the binary KoSIT/BUSINESS outcome symbol ([buildValidationStatusGlyph])
 * plus, right next to it, the advisory AI symbol ([buildAiAdvisoryChip]) — omitted (no second
 * component added) when there is nothing advisory to show for this job.
 */
/** [analyses]/[documentStore]/[aiReportDialog] bundled so [buildValidationCell]/
 *  [buildAiAdvisoryChip] each take one parameter for them instead of three (LongParameterList)
 *  — the advisory-AI data every job cell in this file reads the same way. */
internal data class AiAdvisoryContext(
    val analyses: List<ModelReport>,
    val documentStore: DocumentStore,
    val aiReportDialog: AiReportDialog,
)

internal fun buildValidationCell(
    job: GenerationJobView,
    advisory: AiAdvisoryContext,
    tr: (String, List<Any>) -> String,
): Component = HorizontalLayout().apply {
    isPadding = false; isSpacing = true
    defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
    add(buildValidationStatusGlyph(job, tr))
    buildAiAdvisoryChip(job, advisory, tr)?.let { add(it) }
}

/** The outward validation result is binary; [resolveValidationOutcome] is the single source for
 *  it. The symbol matches the invoice list's validation column; a `NotSuccessful` outcome carries
 *  its reason in the tooltip. */
internal fun buildValidationStatusGlyph(job: GenerationJobView, tr: (String, List<Any>) -> String): StatusGlyph {
    val outcome = resolveValidationOutcome(job)
    val label = when (outcome) {
        is ValidationOutcome.Successful -> tr("invoiceDetail.jobs.kosit.status.valid", emptyList())
        is ValidationOutcome.NotSuccessful -> tr("invoiceDetail.jobs.kosit.status.invalid", emptyList())
        is ValidationOutcome.Pending -> tr("invoiceDetail.jobs.kosit.status.pending", emptyList())
    }
    val detail = if (outcome is ValidationOutcome.NotSuccessful) {
        val reasonText = tr(validationReasonKey(outcome.reason), emptyList())
        if (!outcome.detail.isNullOrBlank()) "$reasonText: ${outcome.detail}" else reasonText
    } else {
        tr("invoiceDetail.jobs.kosit.status.tooltip", emptyList())
    }
    return StatusGlyph(validationOutcomeGlyphIcon(outcome), validationOutcomeTone(job), "$label — $detail", pulsing = outcome is ValidationOutcome.Pending)
}

/**
 * The advisory AI symbol (light bulb; the label is its accessible name and tooltip):
 * "KI: {Bewertung}/10 · {n} Empfehlungen" when a
 * (batched) [analyses] entry carries a rated COMPLETED analysis, "KI-Bericht vorhanden" when only
 * a stored report exists for this job, `null` (no chip at all) when neither does. The actual
 * label/state decision is [resolveAiAdvisoryChipState] — pure, unit-tested; this only adds the
 * per-job [DocumentStore.exists] check and wires the click to open the KI-Volltext dialog.
 * Explicitly neutral tone — never success/error, it is not a verdict on
 * the invoice.
 */
internal fun buildAiAdvisoryChip(
    job: GenerationJobView,
    advisory: AiAdvisoryContext,
    tr: (String, List<Any>) -> String,
): Component? {
    val llmReportKey = parseGenerationJobResultRef(job.resultRefs, "llmReportKey")
    val hasStoredReport = !llmReportKey.isNullOrBlank() &&
        try { advisory.documentStore.exists(llmReportKey) } catch (_: Exception) { false }
    // finding-fixes (2026-08-30) — job.llmStatus is this job's own fallback, see
    // resolveAiAdvisoryChipState's own KDoc for why the invoice-wide analyses/report above no
    // longer are the only source: without it, a genuinely running or failed check for an invoice
    // with no matching InvoiceLlmAnalysis row rendered no chip at all.
    val state = resolveAiAdvisoryChipState(advisory.analyses, hasStoredReport, job.llmStatus)
    val label = when (state) {
        is AiAdvisoryChipState.RatingAndCount ->
            tr("invoiceDetail.jobs.aiChip.ratingAndCount", listOf(state.rating, state.recommendationCount))
        AiAdvisoryChipState.ReportOnly -> tr("invoiceDetail.jobs.aiChip.reportOnly", emptyList())
        // The deferred oMLX stage hasn't resolved yet.
        AiAdvisoryChipState.Pending -> tr("invoiceDetail.jobs.aiChip.pending", emptyList())
        AiAdvisoryChipState.Failed -> tr("invoiceDetail.jobs.aiChip.failed", emptyList())
        AiAdvisoryChipState.Absent -> return null
    }
    val appearance = GlyphAppearance(VaadinIcon.LIGHTBULB, ChipTone.NEUTRAL, label, pulsing = state == AiAdvisoryChipState.Pending)
    val tooltip = "$label — ${tr("invoiceDetail.jobs.aiChip.tooltip", emptyList())}"
    return clickableStatusGlyph(appearance, label, tooltip) { advisory.aiReportDialog.open(job) }.apply {
        addClassName("ai-advisory-chip")
    }
}

/** The "Bericht" column's action — a thin pass-through kept alongside the other cell builders so
 *  a caller assembling the job grid's columns (this panel, and the future global print-jobs view)
 *  reaches every cell/action builder from one place. */
internal fun createValidationReportAction(dialog: ValidationReportDialog, job: GenerationJobView): Component =
    dialog.buildAction(job)
