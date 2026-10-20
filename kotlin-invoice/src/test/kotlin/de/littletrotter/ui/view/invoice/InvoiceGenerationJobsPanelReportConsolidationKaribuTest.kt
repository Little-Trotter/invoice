package de.littletrotter.ui.view.invoice

import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceLlmAnalysis
import de.littletrotter.testing.jobView
import de.littletrotter.application.invoice.port.inbound.GenerationJobView
import de.littletrotter.application.dto.ModelReport
import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._get
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasComponents
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import de.littletrotter.application.invoice.port.inbound.GenerationJobQueryPort
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.LlmAnalysisStatus
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.ValidationMessage
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.invoice.report.ValidationReportDialog
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
 * UX consolidation (PO decision 2026-08-25) — the invoice detail's Druckaufträge grid used to
 * offer four report entry points (a "Bericht anzeigen" button in the latest-job row plus three
 * grid columns: "Prüfbericht", "KI-Prüfung", "Validierungsbericht"). This test covers the
 * consolidation into a single column/dialog (since renamed "Validierungsbefunde", PO 2026-09-03):
 * the old columns/buttons are gone, the combined dialog still reaches every part they used to show
 * (raw KoSIT detail, LLM report), and the graceful-absence behavior for jobs without any report.
 * The grid/latest-job-summary tests construct [InvoiceGenerationJobsPanel] directly
 * (relocated from the former monolithic `InvoiceExportPanel`); the [ValidationReportDialog] tests
 * were already independent of that panel and are unchanged.
 */
class InvoiceGenerationJobsPanelReportConsolidationKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun flatten(component: Component): List<Component> =
        listOf(component) + (component as? HasComponents)
            ?.let { component.children.toList().flatMap { child -> flatten(child) } }
            .orEmpty()

    private fun invoice(id: Long = 1L): Invoice = anInvoice(id = id)

    private fun invoiceDetailDto(id: Long = 1L): de.littletrotter.application.dto.InvoiceDetailDto =
        de.littletrotter.application.dto.InvoiceDetailDto(
            id = id, invoiceNumber = "INV-1", status = 0, invoiceDate = null,
            netAmount = null, vatAmount = null, grossAmount = null,
            sender = null, recipient = null, items = emptyList(), latestGenerationJob = null,
        )

    private fun validKositReport(): String = KositReportPayload.encode(KositValidationResult(status = KositOutcome.VALID))
    private fun invalidKositReport(): String = KositReportPayload.encode(
        KositValidationResult(status = KositOutcome.INVALID, details = listOf(ValidationMessage("ERROR", "BR-01 fehlt")))
    )

    private fun job(
        id: Long = 1L,
        status: GenerationJobStatus = GenerationJobStatus.COMPLETED,
        kositReport: String? = null,
        resultRefs: String? = null,
        inv: Invoice = invoice(),
    ): GenerationJobView = jobView {
        this.id = id
        this.status = status
        this.kositReport = kositReport
        this.resultRefs = resultRefs
        this.invoice = inv
        this.createdAt = OffsetDateTime.now()
    }

    // ── 1. grid: single "Validierungsbericht" column, old columns gone ───────────────────────

    @Test
    fun `grid has exactly one validation report column, no separate check report or AI check columns`() {
        val generationJobQueryPort: GenerationJobQueryPort = mock()
        val theJob = job(kositReport = validKositReport())
        whenever(generationJobQueryPort.findJobViewsByInvoiceId(any())).thenReturn(listOf(theJob))
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())

        val panel = InvoiceGenerationJobsPanel(
            generationJobQueryPort = generationJobQueryPort,
            invoiceDetailQueryPort = invoiceDetailQueryPort,
            documentStore = mock<DocumentStore>(), time = FIXED_TIME_SOURCE,
        )
        panel.render(invoiceDetailDto())

        val grid = flatten(panel).filterIsInstance<Grid<*>>().single().requireAs<Grid<GenerationJobView>>()
        val headers = grid.columns.map { it.headerText }

        val validierungsberichtHeader = UiTranslations.tr("invoiceDetail.jobs.column.validationReport")
        assertEquals(1, headers.count { it == validierungsberichtHeader }, "exactly one combined report column")
        assertFalse(headers.contains("Prüfbericht"), "the raw KoSIT column must be gone")
        assertFalse(headers.contains("KI-Prüfung"), "the separate LLM column must be gone")
        assertEquals(8, headers.size, "ID · Typ · Status · Erstellt · Beendet · Validierung · Validierungsbefunde · Download")
    }

    // ── 2. latest-job row: redundant buttons gone ────────────────────────────────────────────

    @Test
    fun `latest job summary no longer shows the redundant report buttons`() {
        val generationJobQueryPort: GenerationJobQueryPort = mock()
        val theJob = job(
            kositReport = validKositReport(),
            resultRefs = """{"llmReportKey":"reports/llm-1.md"}""",
        )
        whenever(generationJobQueryPort.findJobViewsByInvoiceId(any())).thenReturn(listOf(theJob))
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists(any())).thenReturn(true)

        val panel = InvoiceGenerationJobsPanel(
            generationJobQueryPort = generationJobQueryPort,
            invoiceDetailQueryPort = invoiceDetailQueryPort,
            documentStore = documentStore, time = FIXED_TIME_SOURCE,
        )
        panel.render(invoiceDetailDto())

        val buttons = flatten(panel).filterIsInstance<Button>()
        assertTrue(
            buttons.none { it.text == "Bericht anzeigen" },
            "the redundant KoSIT 'Bericht anzeigen' button in the latest-job row must be gone",
        )
        assertTrue(
            buttons.none { it.text == "KI-Verbesserungsspezifikation" },
            "the redundant LLM button in the latest-job row must be gone (equally redundant with the per-row action)",
        )
    }

    // ── 3. ValidationReportDialog.buildAction: graceful absence vs. present ──────────────────

    @Test
    fun `buildAction shows nothing with an unavailable tooltip when a job has no report at all`() {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists(any())).thenReturn(false)

        val dialog = ValidationReportDialog({ key -> UiTranslations.tr(key) }, invoiceDetailQueryPort, documentStore, time = FIXED_TIME_SOURCE)
        val cell = dialog.buildAction(job(kositReport = null, resultRefs = null))

        assertTrue(cell is Span, "no report at all renders as a dash, not an action button")
        assertEquals("—", (cell as Span).text)
        assertEquals(UiTranslations.tr("invoiceDetail.jobs.report.unavailable"), cell.element.getAttribute("title"))
    }

    @Test
    fun `buildAction opens the combined dialog when only a KoSIT report exists`() {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists(any())).thenReturn(false)

        val dialog = ValidationReportDialog({ key -> UiTranslations.tr(key) }, invoiceDetailQueryPort, documentStore, time = FIXED_TIME_SOURCE)
        val cell = dialog.buildAction(job(kositReport = validKositReport()))

        assertTrue(cell is Button, "a KoSIT-only job still gets the combined report action")
        assertEquals(UiTranslations.tr("invoiceDetail.jobs.validationReport.open"), (cell as Button).text)
    }

    // ── 4. the combined dialog itself reaches both KoSIT detail and LLM sections ─────────────

    @Test
    fun `opening the dialog with only KoSIT data still renders without the KI run and offers the raw KoSIT detail button`() {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists(any())).thenReturn(false)

        val dialog = ValidationReportDialog({ key -> UiTranslations.tr(key) }, invoiceDetailQueryPort, documentStore, time = FIXED_TIME_SOURCE)
        dialog.open(job(kositReport = invalidKositReport()))

        _get<Dialog>()
        val footerButtons = _find<Button>()
        assertTrue(
            footerButtons.any { it.text == UiTranslations.tr("invoiceDetail.jobs.validationReport.kositDetails.open") },
            "the raw KoSIT-detail view (formerly KositReportDialog's own column) must be reachable from inside the combined dialog",
        )
    }

    // ── one download variant only (PO feedback 2026-08-25): the combined "Bericht
    //    herunterladen" already embeds the LLM report (section 3), so the former separate
    //    "Markdown-Bericht herunterladen" button read as a near-duplicate and is gone. ──────────

    @Test
    fun `opening the dialog with a stored LLM report offers only the combined download, not a separate LLM download`() {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists("reports/llm-1.md")).thenReturn(true)
        whenever(documentStore.get("reports/llm-1.md")).thenReturn("# Verbesserungen\n- Feld X korrigieren".toByteArray())

        val dialog = ValidationReportDialog({ key -> UiTranslations.tr(key) }, invoiceDetailQueryPort, documentStore, time = FIXED_TIME_SOURCE)
        dialog.open(job(kositReport = validKositReport(), resultRefs = """{"llmReportKey":"reports/llm-1.md"}"""))

        _get<Dialog>()
        val footerButtons = _find<Button>()
        assertTrue(
            footerButtons.any { it.text == UiTranslations.tr("invoiceDetail.jobs.validationReport.download") },
            "the single combined download button must be present",
        )
        assertEquals(
            1,
            footerButtons.count { it.text == UiTranslations.tr("invoiceDetail.jobs.validationReport.download") },
            "exactly one download-labeled action — no duplicate variant",
        )
    }

    @Test
    fun `opening the dialog with no LLM analyses at all still opens (KI section states no run happened)`() {
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists(any())).thenReturn(false)
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())

        val dialog = ValidationReportDialog({ key -> UiTranslations.tr(key) }, invoiceDetailQueryPort, documentStore, time = FIXED_TIME_SOURCE)
        dialog.open(job(kositReport = validKositReport()))

        // No exception + the dialog attached is the assertion: the markdown content itself
        // (section "2. KI-Prüfung" stating "nicht angefordert") is covered by ValidationReportTest.
        _get<Dialog>()
    }

    @Test
    fun `opening the dialog with a completed LLM analysis present still opens without throwing`() {
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists(any())).thenReturn(false)
        val analysis = anInvoiceLlmAnalysis {
            modelName = "phi-4"
            status = LlmAnalysisStatus.COMPLETED
            rating = 8
            summary = "Sieht gut aus"
        }
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(listOf(analysis).map(ModelReport::from))

        val dialog = ValidationReportDialog({ key -> UiTranslations.tr(key) }, invoiceDetailQueryPort, documentStore, time = FIXED_TIME_SOURCE)
        dialog.open(job(kositReport = validKositReport()))

        _get<Dialog>()
    }

    // ── PO feedback (2026-08-25): the AI report is not displayed ─────────────────────────────
    // Reproduces the reported scenario exactly: a COMPLETED job carrying llmReportKey in
    // resultRefs with a stored report in the document store, but an EMPTY InvoiceLlmAnalysis
    // repository for the invoice (the result-consumer path can resolve llmStatus/llmReportKey
    // into a stored report without ever inserting/finding a per-model row). The rendered dialog
    // body must still contain the AI report text.

    @Test
    fun `PO scenario - stored LLM report with empty analyses repository is rendered in the dialog body`() {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists("invoices/QNN006-2026-06-00001-llm-report.md")).thenReturn(true)
        whenever(documentStore.get("invoices/QNN006-2026-06-00001-llm-report.md")).thenReturn(
            "## Bewertung\n8/10\n\n## Verbesserungsvorschläge\nBT-5 (Waehrungscode) pruefen.".toByteArray()
        )

        val dialog = ValidationReportDialog({ key -> UiTranslations.tr(key) }, invoiceDetailQueryPort, documentStore, time = FIXED_TIME_SOURCE)
        dialog.open(
            job(
                kositReport = validKositReport(),
                resultRefs = """{"llmReportKey":"invoices/QNN006-2026-06-00001-llm-report.md","llmStatus":"COMPLETED"}""",
            )
        )

        val dlg = _get<Dialog>()
        val bodyHtml = flatten(dlg).filterIsInstance<Div>()
            .joinToString("") { it.element.getProperty("innerHTML") ?: "" }

        assertTrue(
            bodyHtml.contains("BT-5") && bodyHtml.contains("pruefen"),
            "the stored AI report text must be visible in the dialog body, got:\n$bodyHtml",
        )
        assertFalse(
            bodyHtml.contains("nicht angefordert (kein oMLX-Lauf)"),
            "section 2 must not claim the AI check was never requested while a report exists, got:\n$bodyHtml",
        )
    }

    // The rendered Markdown body used to carry ad hoc inline overflow-y/height/padding
    // styles with no scrollbar-gutter, so long report lines ended up flush against the scrollbar.
    // validation-report-body (styles.css) replaces that; assert the class made it onto the div
    // since Karibu can't measure the resulting pixel gap.
    @Test
    fun `report body div carries the validation-report-body class`() {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists(any())).thenReturn(false)

        val dialog = ValidationReportDialog({ key -> UiTranslations.tr(key) }, invoiceDetailQueryPort, documentStore, time = FIXED_TIME_SOURCE)
        dialog.open(job(kositReport = validKositReport()))

        val dlg = _get<Dialog>()
        val reportBody = flatten(dlg).filterIsInstance<Div>().find { it.classNames.contains("validation-report-body") }

        assertTrue(reportBody != null, "the rendered report body must carry the validation-report-body class")
    }
}
