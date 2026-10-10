package de.littletrotter.ui.view.invoice

import de.littletrotter.application.dto.ModelReport
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasComponents
import com.vaadin.flow.component.html.NativeButton
import com.vaadin.flow.component.html.Span
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.InvoiceLlmAnalysis
import de.littletrotter.domain.invoice.LlmAnalysisStatus
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.invoice.report.AiReportDialog
import de.littletrotter.ui.view.invoice.report.ValidationReportDialog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * Unit tests for the cell/action builders extracted from [InvoiceExportPanel] into
 * `GenerationJobCells.kt` (a top-level function group, not a linkable type).
 * [AiVisibilityKaribuTest] already covers the AI
 * chip's end-to-end wiring through the full panel; this class targets the extracted functions
 * directly, in particular the attempts-badge/last-error-tooltip addition to the status
 * cell, which had no dedicated test before this extraction.
 */
class GenerationJobCellsTest {

    private val tr: (String, List<Any>) -> String = { key, args -> UiTranslations.tr(key, *args.toTypedArray()) }

    private fun flatten(component: Component): List<Component> =
        listOf(component) + (component as? HasComponents)
            ?.let { component.children.toList().flatMap { child -> flatten(child) } }
            .orEmpty()

    private fun job(
        status: GenerationJobStatus = GenerationJobStatus.COMPLETED,
        attempts: Int = 0,
        lastError: String? = null,
        kositReport: String? = null,
        resultRefs: String? = null,
        llmStatus: String? = null,
        // Defaults to an ISSUED job (no draft symbol) so every pre-existing fixture
        // in this file, which never cared about the flag, keeps rendering exactly as before; the
        // dedicated buildDraftChip tests below pass issued = false explicitly.
        issued: Boolean = true,
    ): GenerationJob = GenerationJob().apply {
        this.status = status
        this.attempts = attempts
        this.lastError = lastError
        this.kositReport = kositReport
        this.resultRefs = resultRefs
        this.llmStatus = llmStatus
        this.issued = issued
    }

    // ── buildJobStatusCell (one symbol, attempts and last error in the tooltip) ─────────────────

    private fun glyphs(component: Component): List<Component> = flatten(component).filter { it.classNames.contains("status-glyph") }

    private fun statusTitle(job: GenerationJob): String = glyphs(buildJobStatusCell(job, tr)).first().element.getAttribute("title")

    @Test
    fun `status cell leaves the attempts count out of the tooltip up to one attempt`() {
        assertTrue(!statusTitle(job(attempts = 0)).contains(UiTranslations.tr("invoiceDetail.jobs.attempts", 0)))
        assertTrue(!statusTitle(job(attempts = 1)).contains(UiTranslations.tr("invoiceDetail.jobs.attempts", 1)))
    }

    @Test
    fun `status cell folds the attempts count into the tooltip once more than one attempt happened`() {
        assertTrue(statusTitle(job(attempts = 3)).contains(UiTranslations.tr("invoiceDetail.jobs.attempts", 3)))
    }

    @Test
    fun `status cell renders no visible text, only the symbol`() {
        val texts = flatten(buildJobStatusCell(job(attempts = 3, lastError = "boom", issued = false), tr))
            .filterIsInstance<Span>().map { it.text }.filter { it.isNotBlank() }
        assertTrue(texts.isEmpty(), "unexpected visible text: $texts")
    }

    @Test
    fun `status cell tooltip is the plain status tooltip without a last error`() {
        val expected = UiTranslations.tr(
            "invoiceDetail.jobs.status.tooltip",
            UiTranslations.tr("invoiceDetail.jobs.status.failed"),
        )
        assertEquals(expected, statusTitle(job(status = GenerationJobStatus.FAILED, lastError = null)))
    }

    @Test
    fun `status cell tooltip folds in the last error when present`() {
        val tooltip = statusTitle(job(status = GenerationJobStatus.FAILED, lastError = "Attempt budget exhausted"))
        assertTrue(tooltip.contains("Attempt budget exhausted"), "tooltip must fold in the last-error text, got: $tooltip")
    }

    @Test
    fun `status cell tooltip ignores a blank last error, same as absent`() {
        val expected = UiTranslations.tr(
            "invoiceDetail.jobs.status.tooltip",
            UiTranslations.tr("invoiceDetail.jobs.status.failed"),
        )
        assertEquals(expected, statusTitle(job(status = GenerationJobStatus.FAILED, lastError = "   ")))
    }

    // ── buildJobStatusCell draft symbol ─────────────────────────────────────────────────────────

    private fun draftTooltip(): String = UiTranslations.tr("invoiceDetail.jobs.draftChip.tooltip")

    @Test
    fun `status cell has no draft symbol for an issued job`() {
        val titles = glyphs(buildJobStatusCell(job(issued = true), tr)).map { it.element.getAttribute("title") }
        assertEquals(1, titles.size)
        assertTrue(titles.none { it == draftTooltip() })
    }

    @Test
    fun `status cell shows a draft symbol for a job whose issued flag is false`() {
        val titles = glyphs(buildJobStatusCell(job(issued = false), tr)).map { it.element.getAttribute("title") }
        assertTrue(titles.contains(draftTooltip()))
    }

    @Test
    fun `status cell shows a draft symbol regardless of the job's own status`() {
        // Even a still-pending/running job already carries its final `issued` value -- set once
        // at creation -- so the symbol must never wait for COMPLETED.
        val titles = glyphs(buildJobStatusCell(job(status = GenerationJobStatus.PENDING, issued = false), tr)).map { it.element.getAttribute("title") }
        assertTrue(titles.contains(draftTooltip()))
    }

    // ── buildDraftGlyph ──────────────────────────────────────────────────────────────────────

    @Test
    fun `buildDraftGlyph is a neutral symbol named by the draft tooltip`() {
        val glyph = buildDraftGlyph(tr)
        assertEquals(draftTooltip(), glyph.element.getAttribute("aria-label"))
        assertTrue(glyph.classNames.contains("status-glyph--neutral"))
    }

    // ── buildJobStatusGlyph ──────────────────────────────────────────────────────────────────

    @Test
    fun `status symbol tone follows the job status and a running job pulses`() {
        assertTrue(buildJobStatusGlyph(GenerationJobStatus.COMPLETED, "x").classNames.contains("status-glyph--ok"))
        assertTrue(buildJobStatusGlyph(GenerationJobStatus.FAILED, "x").classNames.contains("status-glyph--error"))
        assertTrue(buildJobStatusGlyph(GenerationJobStatus.RUNNING, "x").classNames.contains("status-glyph--pulse"))
        assertTrue(!buildJobStatusGlyph(GenerationJobStatus.PENDING, "x").classNames.contains("status-glyph--pulse"))
    }

    @Test
    fun `every job status has its own symbol`() {
        val icons = GenerationJobStatus.entries.map { generationJobStatusGlyphIcon(it) }
        assertEquals(icons.size, icons.toSet().size)
    }

    // ── buildValidationStatusGlyph ───────────────────────────────────────────────────────────

    private fun validKositReport(): String = KositReportPayload.encode(KositValidationResult(status = KositOutcome.VALID))

    @Test
    fun `validation symbol is valid for a successful outcome`() {
        val glyph = buildValidationStatusGlyph(job(kositReport = validKositReport()), tr)
        assertTrue(glyph.classNames.contains("status-glyph--ok"))
        assertTrue(glyph.element.getAttribute("title").startsWith(UiTranslations.tr("invoiceDetail.jobs.kosit.status.valid")))
    }

    @Test
    fun `validation symbol is pending and pulses while the job is still running`() {
        val glyph = buildValidationStatusGlyph(job(status = GenerationJobStatus.RUNNING), tr)
        assertTrue(glyph.classNames.contains("status-glyph--accent"))
        assertTrue(glyph.classNames.contains("status-glyph--pulse"))
    }

    @Test
    fun `validation symbol is invalid with the reason in the tooltip when the job failed`() {
        val glyph = buildValidationStatusGlyph(job(status = GenerationJobStatus.FAILED), tr)
        assertTrue(glyph.classNames.contains("status-glyph--error"))
        assertTrue(glyph.element.getAttribute("title").contains(UiTranslations.tr("validation.reason.aborted")))
    }

    @Test
    fun `validation symbol tooltip appends the detail after a colon when the failure carries one`() {
        val glyph = buildValidationStatusGlyph(
            job(status = GenerationJobStatus.FAILED, resultRefs = """{"error":"engine unreachable"}"""),
            tr,
        )
        val expected = "${UiTranslations.tr("invoiceDetail.jobs.kosit.status.invalid")} — ${UiTranslations.tr("validation.reason.aborted")}: engine unreachable"
        assertEquals(expected, glyph.element.getAttribute("title"))
    }

    // ── buildAiAdvisoryChip / buildValidationCell ────────────────────────────────────────────

    private fun completedAnalysis(rating: Int = 8, issues: String? = "Befund A"): InvoiceLlmAnalysis =
        InvoiceLlmAnalysis().apply {
            modelName = "qwen"
            status = LlmAnalysisStatus.COMPLETED
            this.rating = rating
            this.issues = issues
        }

    private fun aiDialog(invoiceDetailQueryPort: InvoiceDetailQueryPort = mock(), documentStore: DocumentStore = mock()): AiReportDialog =
        AiReportDialog({ key -> UiTranslations.tr(key) }, invoiceDetailQueryPort, documentStore)

    @Test
    fun `AI chip treats a failing documentStore exists check as no stored report, not a crash`() {
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists(any())).thenAnswer { throw RuntimeException("s3 down") }
        val chip = buildAiAdvisoryChip(
            job(resultRefs = """{"llmReportKey":"reports/llm-1.md"}"""),
            AiAdvisoryContext(emptyList(), documentStore, aiDialog(documentStore = documentStore)), tr,
        )
        assertNull(chip, "an exists() failure must fall through to no chip, not propagate")
    }

    @Test
    fun `AI chip is absent when neither a rated analysis nor a stored report exist`() {
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists(any())).thenReturn(false)
        val chip = buildAiAdvisoryChip(job(), AiAdvisoryContext(emptyList(), documentStore, aiDialog(documentStore = documentStore)), tr,)
        assertNull(chip)
    }

    @Test
    fun `AI chip shows rating and count when a rated completed analysis exists`() {
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists(any())).thenReturn(false)
        val chip = buildAiAdvisoryChip(job(), AiAdvisoryContext(listOf(completedAnalysis()).map(ModelReport::from), documentStore, aiDialog(documentStore = documentStore)), tr,)
        assertNotNull(chip)
        assertEquals(UiTranslations.tr("invoiceDetail.jobs.aiChip.ratingAndCount", 8, 1), (chip as NativeButton).element.getAttribute("aria-label"))
        assertTrue(chip.classNames.contains("ai-advisory-chip"))
    }

    // Wiring the AI chip's click to actually open [AiReportDialog.open] end-to-end (through a real
    // Vaadin UI/session) is already covered by AiVisibilityKaribuTest's
    // `clicking the AI chip opens the KI-Volltext dialog` — no need to duplicate that here.

    // finding-fixes (2026-08-30) — this job's own llm_status column is the fallback source once
    // the invoice-wide analyses/report have nothing (see resolveAiAdvisoryChipState's own KDoc):
    // before this fallback, a genuinely running or failed LLM check rendered no chip at all
    // whenever this invoice had no matching InvoiceLlmAnalysis row (the incident this fixes).

    @Test
    fun `AI chip falls back to Pending from the job's own llmStatus when no analysis row exists yet`() {
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists(any())).thenReturn(false)
        val chip = buildAiAdvisoryChip(
            job(llmStatus = "PENDING"), AiAdvisoryContext(emptyList(), documentStore, aiDialog(documentStore = documentStore)), tr,
        )
        assertNotNull(chip)
        assertEquals(UiTranslations.tr("invoiceDetail.jobs.aiChip.pending"), (chip as NativeButton).element.getAttribute("aria-label"))
        assertTrue(chip.classNames.contains("status-glyph--pulse"))
    }

    @Test
    fun `AI chip falls back to Failed from the job's own llmStatus when no analysis row exists`() {
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists(any())).thenReturn(false)
        val chip = buildAiAdvisoryChip(
            job(llmStatus = "FAILED"), AiAdvisoryContext(emptyList(), documentStore, aiDialog(documentStore = documentStore)), tr,
        )
        assertNotNull(chip)
        assertEquals(UiTranslations.tr("invoiceDetail.jobs.aiChip.failed"), (chip as NativeButton).element.getAttribute("aria-label"))
    }

    @Test
    fun `AI chip stays Absent when the job's own llmStatus never requested or ran an LLM check`() {
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists(any())).thenReturn(false)
        val notRequested = buildAiAdvisoryChip(
            job(llmStatus = "NOT_REQUESTED"), AiAdvisoryContext(emptyList(), documentStore, aiDialog(documentStore = documentStore)), tr,
        )
        val disabled = buildAiAdvisoryChip(
            job(llmStatus = "DISABLED"), AiAdvisoryContext(emptyList(), documentStore, aiDialog(documentStore = documentStore)), tr,
        )
        assertNull(notRequested)
        assertNull(disabled)
    }

    @Test
    fun `AI chip prefers a rated analysis over the job's own Failed fallback`() {
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists(any())).thenReturn(false)
        val chip = buildAiAdvisoryChip(
            job(llmStatus = "FAILED"), AiAdvisoryContext(listOf(completedAnalysis()).map(ModelReport::from), documentStore, aiDialog(documentStore = documentStore)), tr,
        )
        assertEquals(UiTranslations.tr("invoiceDetail.jobs.aiChip.ratingAndCount", 8, 1), (chip as NativeButton).element.getAttribute("aria-label"))
    }

    @Test
    fun `validation cell renders both chips when an AI chip is available`() {
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists(any())).thenReturn(false)
        val cell = buildValidationCell(
            job(kositReport = validKositReport()),
            AiAdvisoryContext(listOf(completedAnalysis()).map(ModelReport::from), documentStore, aiDialog(documentStore = documentStore)),

            tr,
        )
        val chips = glyphs(cell)
        assertEquals(2, chips.size)
    }

    @Test
    fun `validation cell renders only the validation chip when there is nothing advisory to show`() {
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists(any())).thenReturn(false)
        val cell = buildValidationCell(
            job(kositReport = validKositReport()),
            AiAdvisoryContext(emptyList(), documentStore, aiDialog(documentStore = documentStore)),

            tr,
        )
        val chips = glyphs(cell)
        assertEquals(1, chips.size)
    }

    // ── createValidationReportAction ─────────────────────────────────────────────────────────

    @Test
    fun `createValidationReportAction delegates to the dialog's own buildAction`() {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists(any())).thenReturn(false)
        val dialog = ValidationReportDialog({ key -> UiTranslations.tr(key) }, invoiceDetailQueryPort, documentStore, time = FIXED_TIME_SOURCE)

        val withReport = createValidationReportAction(dialog, job(kositReport = validKositReport()))
        assertTrue(withReport is com.vaadin.flow.component.button.Button, "a job with a combined report opens the report button")

        val withoutReport = createValidationReportAction(dialog, job(kositReport = null))
        assertTrue(withoutReport is Span, "no combined report at all must render the dash placeholder")
    }

    // ── IM3 — buildStatusTooltip (print-jobs queue Status glyph tooltip assembly) ───────────────

    private val trArgs: (String, List<Any>) -> String = { key, args -> UiTranslations.tr(key, *args.toTypedArray()) }

    @Test
    fun `buildStatusTooltip is only the status clause when attempts is 0 and there is no last error`() {
        val tooltip = buildStatusTooltip(GenerationJobStatus.RUNNING, attempts = 0, lastError = null, tr = trArgs)
        val expected = UiTranslations.tr("invoiceDetail.jobs.status.tooltip", UiTranslations.tr("invoiceDetail.jobs.status.running"))
        assertEquals(expected, tooltip)
    }

    @Test
    fun `buildStatusTooltip omits the attempts clause at exactly one attempt, matching today's gating`() {
        val tooltip = buildStatusTooltip(GenerationJobStatus.COMPLETED, attempts = 1, lastError = null, tr = trArgs)
        assertTrue(!tooltip.contains(UiTranslations.tr("invoiceDetail.jobs.attempts", 1)))
    }

    @Test
    fun `buildStatusTooltip includes the attempts clause once more than one attempt happened`() {
        val tooltip = buildStatusTooltip(GenerationJobStatus.FAILED, attempts = 3, lastError = null, tr = trArgs)
        assertTrue(tooltip.contains(UiTranslations.tr("invoiceDetail.jobs.attempts", 3)), "got: $tooltip")
    }

    @Test
    fun `buildStatusTooltip includes the last-error clause when present`() {
        val tooltip = buildStatusTooltip(GenerationJobStatus.FAILED, attempts = 0, lastError = "worker timeout", tr = trArgs)
        assertTrue(
            tooltip.contains(UiTranslations.tr("invoiceDetail.jobs.lastError.tooltip", "worker timeout")),
            "got: $tooltip",
        )
    }

    @Test
    fun `buildStatusTooltip ignores a blank last error, same as absent`() {
        val tooltip = buildStatusTooltip(GenerationJobStatus.FAILED, attempts = 0, lastError = "   ", tr = trArgs)
        val expected = UiTranslations.tr("invoiceDetail.jobs.status.tooltip", UiTranslations.tr("invoiceDetail.jobs.status.failed"))
        assertEquals(expected, tooltip)
    }

    @Test
    fun `buildStatusTooltip folds all three clauses together when attempts greater than one and a last error are both present`() {
        val tooltip = buildStatusTooltip(GenerationJobStatus.FAILED, attempts = 4, lastError = "db timeout", tr = trArgs)
        val statusLabel = UiTranslations.tr("invoiceDetail.jobs.status.failed")
        val expected = listOf(
            UiTranslations.tr("invoiceDetail.jobs.status.tooltip", statusLabel),
            UiTranslations.tr("invoiceDetail.jobs.attempts", 4),
            UiTranslations.tr("invoiceDetail.jobs.lastError.tooltip", "db timeout"),
        ).joinToString(" — ")
        assertEquals(expected, tooltip)
    }
}
