package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasComponents
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.data.provider.ListDataProvider
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ModelReport
import de.littletrotter.application.invoice.port.inbound.GenerationJobQueryPort
import de.littletrotter.application.invoice.port.inbound.CurrentDocumentView
import de.littletrotter.application.invoice.port.inbound.DocumentReproductionQueryPort
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.application.invoice.port.inbound.GenerationJobView
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.shared.DocumentStore
import de.littletrotter.ui.component.ChipTone
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.StatusGlyph
import de.littletrotter.ui.component.asActionColumn
import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.format.DateFormatter
import de.littletrotter.shared.GermanTime
import java.time.format.DateTimeFormatter
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.invoice.report.AiReportDialog
import de.littletrotter.ui.view.invoice.report.ValidationReportDialog
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject

/**
 * Extracted from `InvoiceExportPanel`: the Versionen-tab's job Grid, latest-job summary
 * and Prüfberichte (validation/AI report dialogs). [InvoiceExportPanel] composes this alongside
 * [InvoicePrintControls] into the same card.
 *
 * Builds the Grid unconditionally and toggles
 * Grid-vs-empty-state visibility from the very same jobs list the Grid is filled from, rather
 * than deciding the empty state as a separate structural branch. The Grid and its
 * [ListDataProvider] are built once and held; every [refresh] after the first only swaps the
 * provider's items — Grid identity, scroll position and selection all survive a refresh.
 */
@Dependent
class InvoiceGenerationJobsPanel
@Inject
constructor(
    private val generationJobQueryPort: GenerationJobQueryPort,
    private val invoiceDetailQueryPort: InvoiceDetailQueryPort,
    private val documentStore: DocumentStore,
    private val time: TimeSource,
    /** Null in component tests that do not consider reproductions; then no job carries the tag. */
    private val documentReproductionQueryPort: DocumentReproductionQueryPort? = null,
) : Div(), TranslationProvider {

    // The advisory AI chip's click target; shares the exact same
    // stored-report/fallback resolution as ValidationReportDialog's own "KI-Bericht anzeigen"
    // footer button (both instantiate AiReportDialog the same way, no shared mutable state).
    private val aiReportDialog = AiReportDialog({ key -> tr(key) }, invoiceDetailQueryPort, documentStore)

    // PO decision 2026-08-25 — the grid used to offer three separate report
    // entry points (raw KoSIT, LLM, combined). They are now one: [ValidationReportDialog] embeds
    // the raw KoSIT detail and the LLM download itself.
    private val validationReportDialog = ValidationReportDialog(
        { key -> tr(key) },
        invoiceDetailQueryPort,
        documentStore,
        resolveMessageKey = { key, args ->
            UiTranslations.trOrNull(
                this,
                key,
                *args.mapIndexed { index, argument -> InvoiceUnitLabels.validationArgument(this, key, index, argument) }.toTypedArray(),
            )
        },
        time = time,
    )

    private var latestSummarySlot: Div? = null
    private var jobsGridSlot: Div? = null
    private var jobsGrid: DataTable<GenerationJobView>? = null
    private var jobsDataProvider: ListDataProvider<GenerationJobView>? = null
    private var jobsEmptyState: Paragraph? = null

    /** The InvoiceLlmAnalysis rows for the currently rendered jobs — read by the Grid's
     *  component-column closures (built once in [buildJobsGrid]) via this field rather than a
     *  captured local, so a later [refresh] with fresh analyses is picked up the next time the
     *  Grid re-renders a row, without rebuilding the Grid itself. */
    private var currentAnalyses: List<ModelReport> = emptyList()

    /** The invoice's document of record, loaded once per refresh so the grid rows and the latest-job
     *  summary decide on the reproduction tag without a query per row. */
    private var currentDocument: CurrentDocumentView? = null

    init { setWidthFull() }

    override fun getComponent(): Component = this

    fun render(invoice: InvoiceDetailDto) {
        removeAll()
        latestSummarySlot = null
        jobsGridSlot = null
        jobsGrid = null
        jobsDataProvider = null
        jobsEmptyState = null

        val summary = Div().apply { setWidthFull() }
        latestSummarySlot = summary
        add(summary)

        val gridSlot = Div().apply { setWidthFull() }
        jobsGridSlot = gridSlot
        add(gridSlot)

        renderJobsSection(invoice)
    }

    /** Rebuilds only [latestSummarySlot]; never rebuilds the Grid (see [renderJobsSection]). */
    fun refresh(invoice: InvoiceDetailDto) {
        if (jobsGridSlot == null) {
            render(invoice)
            return
        }
        renderJobsSection(invoice)
    }

    private fun renderJobsSection(current: InvoiceDetailDto) {
        val gridSlot = jobsGridSlot ?: return
        val jobs = generationJobQueryPort.findJobViewsByInvoiceId(current.id)

        // One batched fetch for the whole panel (InvoiceLlmAnalysis rows are
        // per-INVOICE, not per-job — every job row shares this same list), reused by both the
        // latest-job summary and every grid row's advisory AI chip. No per-row query.
        val analyses = if (jobs.isNotEmpty()) invoiceDetailQueryPort.findLlmAnalyses(current.id) else emptyList()
        currentAnalyses = analyses
        currentDocument = documentReproductionQueryPort?.currentDocument(current.id)

        latestSummarySlot?.apply {
            removeAll()
            if (jobs.isNotEmpty()) add(buildLatestJobSummary(jobs, analyses))
        }

        val grid = jobsGrid ?: buildJobsGrid().also {
            jobsGrid = it
            gridSlot.add(it)
        }
        val emptyState = jobsEmptyState ?: Paragraph(tr("invoiceDetail.jobs.empty")).also {
            jobsEmptyState = it
            gridSlot.add(it)
        }
        val provider = jobsDataProvider ?: ListDataProvider(mutableListOf<GenerationJobView>()).also {
            jobsDataProvider = it
            grid.setItems(it)
        }

        provider.items.clear()
        provider.items.addAll(jobs)
        provider.refreshAll()

        grid.isVisible = jobs.isNotEmpty()
        emptyState.isVisible = jobs.isEmpty()
    }

    /** The job Grid's column layout — built exactly once (see [renderJobsSection]) and never
     *  rebuilt; every subsequent refresh only swaps its data provider's items. Component-column
     *  closures read [currentAnalyses] rather than a captured `analyses` local so a later refresh
     *  with fresh analyses is picked up the next time the Grid re-renders a row. */
    private fun buildJobsGrid(): DataTable<GenerationJobView> = DataTable(GenerationJobView::class.java, false).apply {
        setWidthFull()
        isAllRowsVisible = true
        addColumn { it.id }.setHeader(tr("invoiceDetail.jobs.column.id")).asCompactColumn()
        addColumn { it.jobType }.setHeader(tr("invoiceDetail.jobs.column.type")).asCompactColumn()
        addComponentColumn { job -> withReproductionTag(buildJobStatusCell(job, ::trArgs), job) }.setHeader(tr("invoiceDetail.jobs.column.status")).asCompactColumn()
        addComponentColumn { buildDateTimeCell(it.createdAt) }.setHeader(tr("invoiceDetail.jobs.column.createdAt")).asCompactColumn()
        addComponentColumn { buildDateTimeCell(it.finishedAt) }.setHeader(tr("invoiceDetail.jobs.column.finishedAt")).asCompactColumn()
        addComponentColumn { buildValidationCell(it, AiAdvisoryContext(currentAnalyses, documentStore, aiReportDialog), ::trArgs) }
            .setHeader(tr("invoiceDetail.jobs.column.validation")).asCompactColumn()
        addComponentColumn { createValidationReportAction(validationReportDialog, it) }
            .setHeader(tr("invoiceDetail.jobs.column.validationReport")).asCompactColumn()
        // PO-Meldung 05.09.2026: "bei 100 % Darstellung ist der Download-Bereich nicht mehr ganz zu
        // sehen." Acht Spalten, sechs davon mit automatischer Breite, brauchen bei 1280 px mit
        // geöffneter Navigationsleiste mehr Platz als da ist — und Download war die LETZTE, lag
        // also vollständig hinter dem rechten Rand. Sichtbar blieben die Diagnosespalten, die
        // Handlung war weg.
        //
        // Ans Ende geheftet statt Spalten zu entfernen oder Breiten zu raten: Download ist die
        // einzige Spalte, wegen der man diese Tabelle überhaupt aufsucht, und geheftet bleibt sie
        // bei JEDER Fensterbreite erreichbar, während Erstellt/Beendet/Validierung darunter
        // wegscrollen. Nur diese eine Spalte — jede weitere geheftete nimmt dem scrollenden Teil
        // die Breite, die ihn lesbar macht.
        addComponentColumn { buildDownloadAction(it, documentStore) { key -> tr(key) } }
            .asActionColumn().setHeader(tr("invoiceDetail.jobs.column.download"))
    }

    /** Appends the reproduction tag to a status cell when [job] produced the reproduced document. */
    private fun withReproductionTag(cell: Component, job: GenerationJobView): Component {
        val tag = reproductionTag(job) ?: return cell
        (cell as? HasComponents)?.add(tag)
        return cell
    }

    /** A reproduction is never a byte-identical original; the tag marks the one job whose result is
     *  the current document of record and carries the date so nobody mistakes it for the original. */
    private fun reproductionTag(job: GenerationJobView): StatusGlyph? {
        val document = currentDocument ?: return null
        if (!document.reproduced || job.id == null || job.id != document.jobId) return null
        val reproducedAt = document.reproducedAt?.atZoneSameInstant(GermanTime.ZONE)
        val formatted = reproducedAt?.let { "${DateFormatter.format(it.toLocalDate())} ${TIME_FORMAT.format(it)}" } ?: "—"
        return StatusGlyph(VaadinIcon.COPY, ChipTone.WARN, tr("document.reproduction.tooltip", formatted))
    }

    /** Wires this panel's component-aware `tr(key, vararg params)` into the `(key, args) ->
     *  String` shape `GenerationJobCells`' extracted builders take. */
    private fun trArgs(key: String, args: List<Any>): String = tr(key, *args.toTypedArray())

    private fun buildLatestJobSummary(jobs: List<GenerationJobView>, analyses: List<ModelReport>): Div {
        val latestJob = jobs.maxByOrNull { it.createdAt } ?: return Div()
        return Div().apply {
            addClassName("lt-job-summary")
            add(Paragraph(tr("invoiceDetail.jobs.latest", latestJob.id ?: "n/a")).apply { addClassName("lt-job-summary__title") })
            add(buildJobStatusGlyph(latestJob.status, buildStatusTooltip(latestJob.status, latestJob.attempts, latestJob.lastError, ::trArgs)))
            reproductionTag(latestJob)?.let { add(it) }
            add(buildValidationStatusGlyph(latestJob, ::trArgs))
            buildAiAdvisoryChip(latestJob, AiAdvisoryContext(analyses, documentStore, aiReportDialog), ::trArgs)?.let { add(it) }
            buildValidationReasonLine(latestJob)?.let { add(it) }
            resolveFormatLabel(latestJob)?.let {
                add(Span(it).apply { addClassName("lt-job-note") })
            }
            // UX consolidation (PO decision 2026-08-25) — the "Bericht anzeigen"/"KI-Verbesserungs-
            // spezifikation" buttons that used to live here were redundant duplicates of the grid's
            // per-row report action for this same (latest) job; removed in favor of the single
            // "Validierungsbericht" column action below.
        }
    }

    /** The reason line shown under the validation symbol in [buildLatestJobSummary] — `null` for
     *  every outcome that isn't `NotSuccessful` (a successful/pending chip needs no extra line). */
    private fun buildValidationReasonLine(job: GenerationJobView): Span? {
        val outcome = resolveValidationOutcome(job)
        if (outcome !is ValidationOutcome.NotSuccessful) return null
        val reasonText = tr(validationReasonKey(outcome.reason))
        val text = if (!outcome.detail.isNullOrBlank()) "$reasonText: ${outcome.detail}" else reasonText
        return Span(text).apply { addClassName("lt-job-note") }
    }
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
