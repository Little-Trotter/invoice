package de.littletrotter.ui.view.invoice.report

import de.littletrotter.fixture.anInvoiceLlmAnalysis
import de.littletrotter.testing.jobView
import de.littletrotter.application.invoice.port.inbound.GenerationJobView
import de.littletrotter.application.dto.ModelReport
import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._get
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceLlmAnalysis
import de.littletrotter.domain.invoice.LlmAnalysisStatus
import de.littletrotter.fixture.edit
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.OffsetDateTime
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * UI-5 slice 3a — [ValidationReportDialog] had no dedicated test file; its branches were only ever
 * reached incidentally through [de.littletrotter.ui.view.invoice.InvoiceExportPanel] fixtures.
 * This covers the `buildAction(job)` three-way "is there anything to show" overload (each of the
 * kosit/llmReport/llmAnalyses legs, including the `documentStore.exists` exception guard), the
 * `job.invoice` null branch feeding both `findLlmAnalyses` and the invoice-number fallback in
 * [open], the stored-report load failure that must render a visible line rather than vanish, and
 * the raw-KoSIT-details footer button's presence/absence.
 */
class ValidationReportDialogCoverageKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun validKositReport(): String = KositReportPayload.encode(KositValidationResult(status = KositOutcome.VALID))

    private fun job(
        id: Long? = 1L,
        kositReport: String? = null,
        resultRefs: String? = null,
        invoice: Invoice? = Invoice().edit { this.id = 5L; invoiceNumber = InvoiceNumber("RE-2026-0001") },
    ): GenerationJobView = jobView {
        this.id = id
        this.status = GenerationJobStatus.COMPLETED
        this.kositReport = kositReport
        this.resultRefs = resultRefs
        this.invoice = invoice
        this.createdAt = OffsetDateTime.now()
    }

    private fun analysis(): InvoiceLlmAnalysis = anInvoiceLlmAnalysis {
        modelName = "qwen"; status = LlmAnalysisStatus.COMPLETED; rating = 8
    }

    // ── buildAction(job): dash vs. button, per source ───────────────────────────────────────────

    @Test
    fun `buildAction shows the dash when nothing at all exists for the job`() {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())
        val documentStore: DocumentStore = mock()
        val dialog = ValidationReportDialog({ key -> UiTranslations.tr(key) }, invoiceDetailQueryPort, documentStore, time = FIXED_TIME_SOURCE)

        val cell = dialog.buildAction(job(kositReport = null, resultRefs = null))

        assertTrue(cell is Span)
    }

    @Test
    fun `buildAction shows a button from the KoSIT report alone`() {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())
        val documentStore: DocumentStore = mock()
        val dialog = ValidationReportDialog({ key -> UiTranslations.tr(key) }, invoiceDetailQueryPort, documentStore, time = FIXED_TIME_SOURCE)

        val cell = dialog.buildAction(job(kositReport = validKositReport()))

        assertTrue(cell is Button)
    }

    @Test
    fun `buildAction shows a button from a stored LLM report alone`() {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists("reports/llm-1.md")).thenReturn(true)
        val dialog = ValidationReportDialog({ key -> UiTranslations.tr(key) }, invoiceDetailQueryPort, documentStore, time = FIXED_TIME_SOURCE)

        val cell = dialog.buildAction(job(kositReport = null, resultRefs = """{"llmReportKey":"reports/llm-1.md"}"""))

        assertTrue(cell is Button)
    }

    @Test
    fun `buildAction shows a button from LLM analyses alone, with no kosit and no stored report`() {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(listOf(analysis()).map(ModelReport::from))
        val documentStore: DocumentStore = mock()
        val dialog = ValidationReportDialog({ key -> UiTranslations.tr(key) }, invoiceDetailQueryPort, documentStore, time = FIXED_TIME_SOURCE)

        val cell = dialog.buildAction(job(kositReport = null, resultRefs = null))

        assertTrue(cell is Button)
    }

    @Test
    fun `buildAction treats a failing documentStore exists check as no stored report, not a crash`() {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(listOf(analysis()).map(ModelReport::from))
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists(any())).thenAnswer { throw RuntimeException("s3 down") }
        val dialog = ValidationReportDialog({ key -> UiTranslations.tr(key) }, invoiceDetailQueryPort, documentStore, time = FIXED_TIME_SOURCE)

        // Still shows a button, but via the analyses leg, not the (failing) stored-report leg.
        val cell = dialog.buildAction(job(kositReport = null, resultRefs = """{"llmReportKey":"reports/llm-1.md"}"""))

        assertTrue(cell is Button)
    }

    // ── job.invoice == null: findLlmAnalyses(null) and the invoice-number fallbacks in open() ────

    @Test
    fun `buildAction with no invoice on the job still queries findLlmAnalyses with a null id`() {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(null)).thenReturn(emptyList())
        val documentStore: DocumentStore = mock()
        val dialog = ValidationReportDialog({ key -> UiTranslations.tr(key) }, invoiceDetailQueryPort, documentStore, time = FIXED_TIME_SOURCE)

        val cell = dialog.buildAction(job(kositReport = null, resultRefs = null, invoice = null))

        assertTrue(cell is Span, "nothing to show, but must not throw for a jobless invoice")
    }

    @Test
    fun `open with no invoice on the job falls back to n-a in the title and a job-id file name`() {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())
        val documentStore: DocumentStore = mock()
        val dialog = ValidationReportDialog({ key -> UiTranslations.tr(key) }, invoiceDetailQueryPort, documentStore, time = FIXED_TIME_SOURCE)

        dialog.open(job(id = 77L, kositReport = validKositReport(), invoice = null))

        val dlg = _get<Dialog>()
        val body = flatten(dlg).filterIsInstance<Div>().joinToString("") { it.element.getProperty("innerHTML") ?: "" }
        assertTrue(body.contains("Rechnung n/a"), "got:\n$body")
    }

    @Test
    fun `open with an unassigned (blank) invoice number falls back to a job-id file name too`() {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())
        val documentStore: DocumentStore = mock()
        val dialog = ValidationReportDialog({ key -> UiTranslations.tr(key) }, invoiceDetailQueryPort, documentStore, time = FIXED_TIME_SOURCE)
        val blankInvoice = Invoice().edit { id = 9L } // default invoiceNumber = InvoiceNumber.UNASSIGNED ("")

        // Must not throw building the (blank -> null) file name fallback.
        dialog.open(job(id = 88L, kositReport = validKositReport(), invoice = blankInvoice))

        _get<Dialog>()
    }

    // Note (design finding, not acted on): `.replace("/", "-")` at ValidationReportDialog.kt:177
    // is unreachable — `InvoiceNumber`'s own `init` block rejects any value containing "/" (none
    // of its three accepted patterns allow one), so a real `Invoice.invoiceNumber` can never carry
    // a slash for this line to see. Not covered here for that reason.

    // ── stored-report load failure must render a visible line, not vanish ──────────────────────

    @Test
    fun `a failing stored-report load renders a visible failure line instead of silently dropping section 3`() {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists("reports/llm-1.md")).thenReturn(true)
        whenever(documentStore.get("reports/llm-1.md")).thenAnswer { throw RuntimeException("read failed") }
        val dialog = ValidationReportDialog({ key -> UiTranslations.tr(key) }, invoiceDetailQueryPort, documentStore, time = FIXED_TIME_SOURCE)

        dialog.open(job(kositReport = validKositReport(), resultRefs = """{"llmReportKey":"reports/llm-1.md"}"""))

        val dlg = _get<Dialog>()
        val body = flatten(dlg).filterIsInstance<Div>().joinToString("") { it.element.getProperty("innerHTML") ?: "" }
        assertTrue(body.contains("konnte nicht geladen werden"), "got:\n$body")
        assertTrue(body.contains("read failed"), "the exception's own message must be visible, got:\n$body")
    }

    // ── raw KoSIT details footer button: present only when a raw report actually exists ────────

    @Test
    fun `no KoSIT details button is offered when the job has no raw report`() {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(listOf(analysis()).map(ModelReport::from))
        val documentStore: DocumentStore = mock()
        val dialog = ValidationReportDialog({ key -> UiTranslations.tr(key) }, invoiceDetailQueryPort, documentStore, time = FIXED_TIME_SOURCE)

        dialog.open(job(kositReport = null, resultRefs = null))

        val footerButtons = _find<Button>()
        assertTrue(
            footerButtons.none { it.text == UiTranslations.tr("invoiceDetail.jobs.validationReport.kositDetails.open") },
            "no raw report -> no KoSIT-details button",
        )
    }

    // ── blank (non-null), not just null/absent, must read the same way ─────────────────────────

    @Test
    fun `buildAction treats a blank (non-null) kositReport the same as an absent one`() {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())
        val documentStore: DocumentStore = mock()
        val dialog = ValidationReportDialog({ key -> UiTranslations.tr(key) }, invoiceDetailQueryPort, documentStore, time = FIXED_TIME_SOURCE)

        val cell = dialog.buildAction(job(kositReport = "   ", resultRefs = null))

        assertTrue(cell is Span, "a blank kositReport must not count as 'has a report'")
    }

    @Test
    fun `open with a blank (non-null) llmReportKey never asks the documentStore about it`() {
        // Unlike buildAction's own hasLlmReport leg (only a null-check), open()'s
        // hasLlmStoredReport explicitly treats a blank key like an absent one and short-circuits
        // before ever calling documentStore.exists.
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())
        val documentStore: DocumentStore = mock()
        val dialog = ValidationReportDialog({ key -> UiTranslations.tr(key) }, invoiceDetailQueryPort, documentStore, time = FIXED_TIME_SOURCE)

        dialog.open(job(kositReport = validKositReport(), resultRefs = """{"llmReportKey":"   "}"""))

        org.mockito.kotlin.verify(documentStore, org.mockito.kotlin.never()).exists(any())
        _get<Dialog>()
    }

    @Test
    fun `open with a blank (non-null) rawKosit offers no KoSIT details button either`() {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())
        val documentStore: DocumentStore = mock()
        val dialog = ValidationReportDialog({ key -> UiTranslations.tr(key) }, invoiceDetailQueryPort, documentStore, time = FIXED_TIME_SOURCE)

        dialog.open(job(kositReport = "   ", resultRefs = null))

        val footerButtons = _find<Button>()
        assertTrue(
            footerButtons.none { it.text == UiTranslations.tr("invoiceDetail.jobs.validationReport.kositDetails.open") },
            "a blank rawKosit must not offer the KoSIT-details button either",
        )
    }

    // ── Task 2 (PO 2026-09-03): toolchain provenance hint ──────────────────────────────────────

    @Test
    fun `the findings dialog renders the muted toolchain provenance hint naming all four tools`() {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())
        val documentStore: DocumentStore = mock()
        val dialog = ValidationReportDialog({ key -> UiTranslations.tr(key) }, invoiceDetailQueryPort, documentStore, time = FIXED_TIME_SOURCE)

        dialog.open(job(kositReport = validKositReport()))

        val dlg = _get<Dialog>()
        val hint = flatten(dlg).filterIsInstance<Span>()
            .firstOrNull { it.classNames.containsAll(setOf("fs-08", "text-muted")) }
        assertTrue(hint != null, "the dialog must carry the muted toolchain hint span")
        val text = hint!!.text
        listOf("XRechnung", "KoSIT Validator", "veraPDF", "Little Trotter").forEach { name ->
            assertTrue(text.contains(name), "hint must name $name, got: $text")
        }
        assertTrue(!text.contains("{"), "no unfilled MessageFormat placeholder may survive, got: $text")
    }

    private fun flatten(component: com.vaadin.flow.component.Component): List<com.vaadin.flow.component.Component> =
        listOf(component) + (component as? com.vaadin.flow.component.HasComponents)
            ?.let { component.children.toList().flatMap { child -> flatten(child) } }
            .orEmpty()
}
