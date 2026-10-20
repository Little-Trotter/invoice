package de.littletrotter.ui.view.invoice

import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceLlmAnalysis
import de.littletrotter.testing.jobView
import de.littletrotter.application.invoice.port.inbound.GenerationJobView
import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._get
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasComponents
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.NativeButton
import de.littletrotter.application.dto.ModelReport
import de.littletrotter.application.invoice.port.inbound.GenerationJobQueryPort
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceLlmAnalysis
import de.littletrotter.domain.invoice.LlmAnalysisStatus
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.invoice.report.AiReportDialog
import de.littletrotter.ui.view.invoice.report.ValidationReportDialog
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
 * KI-Ergebnis sichtbar: advisory AI chip (decision 1), the two report copy buttons
 * (decision 2) and the KI-Volltext dialog (decision 3). Decision 4 (finding↔recommendation
 * cross-linking) is covered as a pure function in [ValidationReportTest] — this class is UI
 * wiring only.
 */
class AiVisibilityKaribuTest {

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

    private fun completedAnalysis(model: String = "qwen", rating: Int = 8, issues: String? = "Befund A"): InvoiceLlmAnalysis =
        anInvoiceLlmAnalysis {
            modelName = model
            this.status = LlmAnalysisStatus.COMPLETED
            this.rating = rating
            this.issues = issues
        }

    // ── advisory AI chip (decision 1) ─────────────────────────────────────────────────────────

    @Test
    fun `AI chip shows rating and recommendation count when a rated completed analysis exists`() {
        val generationJobQueryPort: GenerationJobQueryPort = mock()
        whenever(generationJobQueryPort.findJobViewsByInvoiceId(any())).thenReturn(listOf(job(kositReport = validKositReport())))
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(listOf(completedAnalysis()).map(ModelReport::from))
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists(any())).thenReturn(false)

        val panel = InvoiceGenerationJobsPanel(
            generationJobQueryPort = generationJobQueryPort,
            invoiceDetailQueryPort = invoiceDetailQueryPort,
            documentStore = documentStore, time = FIXED_TIME_SOURCE,
        )
        panel.render(invoiceDetailDto())

        val aiChip = flatten(panel).filterIsInstance<NativeButton>()
            .find { it.classNames.contains("ai-advisory-chip") }
        assertTrue(aiChip != null, "the advisory AI chip must be rendered")
        assertEquals(UiTranslations.tr("invoiceDetail.jobs.aiChip.ratingAndCount", 8, 1), aiChip!!.element.getAttribute("aria-label"))
    }

    @Test
    fun `AI chip shows report-vorhanden when no rated analysis exists but a report is stored`() {
        val generationJobQueryPort: GenerationJobQueryPort = mock()
        val theJob = job(kositReport = validKositReport(), resultRefs = """{"llmReportKey":"reports/llm-1.md"}""")
        whenever(generationJobQueryPort.findJobViewsByInvoiceId(any())).thenReturn(listOf(theJob))
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists("reports/llm-1.md")).thenReturn(true)

        val panel = InvoiceGenerationJobsPanel(
            generationJobQueryPort = generationJobQueryPort,
            invoiceDetailQueryPort = invoiceDetailQueryPort,
            documentStore = documentStore, time = FIXED_TIME_SOURCE,
        )
        panel.render(invoiceDetailDto())

        val aiChip = flatten(panel).filterIsInstance<NativeButton>()
            .find { it.classNames.contains("ai-advisory-chip") }
        assertTrue(aiChip != null, "the advisory AI chip must be rendered")
        assertEquals(UiTranslations.tr("invoiceDetail.jobs.aiChip.reportOnly"), aiChip!!.element.getAttribute("aria-label"))
    }

    @Test
    fun `AI chip is absent with neither analyses nor a stored report`() {
        val generationJobQueryPort: GenerationJobQueryPort = mock()
        whenever(generationJobQueryPort.findJobViewsByInvoiceId(any())).thenReturn(listOf(job(kositReport = validKositReport())))
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists(any())).thenReturn(false)

        val panel = InvoiceGenerationJobsPanel(
            generationJobQueryPort = generationJobQueryPort,
            invoiceDetailQueryPort = invoiceDetailQueryPort,
            documentStore = documentStore, time = FIXED_TIME_SOURCE,
        )
        panel.render(invoiceDetailDto())

        val aiChip = flatten(panel).filterIsInstance<NativeButton>()
            .find { it.classNames.contains("ai-advisory-chip") }
        assertNull(aiChip, "no chip must be rendered when there is nothing advisory to show")
    }

    @Test
    fun `AI chip fetches LLM analyses exactly once per render, not once per job row`() {
        val generationJobQueryPort: GenerationJobQueryPort = mock()
        whenever(generationJobQueryPort.findJobViewsByInvoiceId(any())).thenReturn(
            listOf(job(id = 1L, kositReport = validKositReport()), job(id = 2L, kositReport = validKositReport())),
        )
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(listOf(completedAnalysis()).map(ModelReport::from))
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists(any())).thenReturn(false)

        val panel = InvoiceGenerationJobsPanel(
            generationJobQueryPort = generationJobQueryPort,
            invoiceDetailQueryPort = invoiceDetailQueryPort,
            documentStore = documentStore, time = FIXED_TIME_SOURCE,
        )
        panel.render(invoiceDetailDto())

        // Guardrail — one batched fetch for the whole panel (findLlmAnalyses is
        // invoice-scoped, not job-scoped), reused by the latest-job summary and every grid row's
        // chip; the pre-existing per-row ValidationReportDialog.buildAction call is untouched.
        org.mockito.kotlin.verify(invoiceDetailQueryPort, org.mockito.kotlin.times(1)).findLlmAnalyses(anyOrNull())
    }

    @Test
    fun `clicking the AI chip opens the KI-Volltext dialog`() {
        val generationJobQueryPort: GenerationJobQueryPort = mock()
        whenever(generationJobQueryPort.findJobViewsByInvoiceId(any())).thenReturn(listOf(job(kositReport = validKositReport())))
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(listOf(completedAnalysis()).map(ModelReport::from))
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists(any())).thenReturn(false)

        val panel = InvoiceGenerationJobsPanel(
            generationJobQueryPort = generationJobQueryPort,
            invoiceDetailQueryPort = invoiceDetailQueryPort,
            documentStore = documentStore, time = FIXED_TIME_SOURCE,
        )
        panel.render(invoiceDetailDto())

        val aiChip = flatten(panel).filterIsInstance<NativeButton>().first { it.classNames.contains("ai-advisory-chip") }
        aiChip._click()

        val dlg = _get<Dialog>()
        assertEquals(UiTranslations.tr("invoiceDetail.jobs.aiReport.dialog.title"), dlg.headerTitle)
    }

    // ── KI-Volltext dialog (decision 3) ───────────────────────────────────────────────────────

    @Test
    fun `AiReportDialog renders the full unmodified stored markdown, no heading demotion`() {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists("reports/llm-1.md")).thenReturn(true)
        whenever(documentStore.get("reports/llm-1.md")).thenReturn("# Bewertung\n8/10 fuer BT-5.".toByteArray())

        val dialog = AiReportDialog({ key -> UiTranslations.tr(key) }, invoiceDetailQueryPort, documentStore)
        dialog.open(job(resultRefs = """{"llmReportKey":"reports/llm-1.md"}"""))

        val dlg = _get<Dialog>()
        val bodyHtml = flatten(dlg).filterIsInstance<Div>().joinToString("") { it.element.getProperty("innerHTML") ?: "" }
        assertTrue(bodyHtml.contains("<h1>"), "the raw '# ' heading must render as an actual <h1>, not be demoted, got:\n$bodyHtml")
        assertTrue(bodyHtml.contains("BT-5"))
    }

    @Test
    fun `AiReportDialog falls back to a synthesized markdown from analysis rows when nothing is stored`() {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(listOf(completedAnalysis(model = "gemma", rating = 7, issues = "Waehrungscode pruefen")).map(ModelReport::from))
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists(any())).thenReturn(false)

        val dialog = AiReportDialog({ key -> UiTranslations.tr(key) }, invoiceDetailQueryPort, documentStore)
        dialog.open(job(resultRefs = null))

        val dlg = _get<Dialog>()
        val bodyHtml = flatten(dlg).filterIsInstance<Div>().joinToString("") { it.element.getProperty("innerHTML") ?: "" }
        assertTrue(bodyHtml.contains("gemma"))
        assertTrue(bodyHtml.contains("Waehrungscode"))
    }

    @Test
    fun `AiReportDialog offers a copy button that fires the clipboard JS without throwing`() {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists("reports/llm-1.md")).thenReturn(true)
        whenever(documentStore.get("reports/llm-1.md")).thenReturn("Nur KI-Text.".toByteArray())

        val dialog = AiReportDialog({ key -> UiTranslations.tr(key) }, invoiceDetailQueryPort, documentStore)
        dialog.open(job(resultRefs = """{"llmReportKey":"reports/llm-1.md"}"""))

        val copyButton = _find<Button>().single { it.text == UiTranslations.tr("invoiceDetail.jobs.report.copy") }
        copyButton.click() // executeJs is queued, not executed by a real browser here — must not throw
    }

    // ── ValidationReportDialog footer gains the two new buttons (decision 2/3) ───────────────

    @Test
    fun `ValidationReportDialog footer gains the copy button without losing existing buttons`() {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists(any())).thenReturn(false)

        val dialog = ValidationReportDialog({ key -> UiTranslations.tr(key) }, invoiceDetailQueryPort, documentStore, time = FIXED_TIME_SOURCE)
        dialog.open(job(kositReport = validKositReport()))

        val footerButtons = _find<Button>()
        assertTrue(
            footerButtons.any { it.text == UiTranslations.tr("invoiceDetail.jobs.report.copy") },
            "the combined-report copy button must be present",
        )
        assertTrue(
            footerButtons.any { it.text == UiTranslations.tr("invoiceDetail.jobs.validationReport.download") },
            "the existing download button must still be present",
        )
        assertTrue(
            footerButtons.any { it.text == UiTranslations.tr("app.ok") },
            "the existing close button must still be present",
        )
    }

    @Test
    fun `ValidationReportDialog footer offers KI-Bericht anzeigen only when a stored report exists`() {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists("reports/llm-1.md")).thenReturn(true)
        whenever(documentStore.get("reports/llm-1.md")).thenReturn("KI-Text".toByteArray())

        val dialog = ValidationReportDialog({ key -> UiTranslations.tr(key) }, invoiceDetailQueryPort, documentStore, time = FIXED_TIME_SOURCE)
        dialog.open(job(kositReport = validKositReport(), resultRefs = """{"llmReportKey":"reports/llm-1.md"}"""))

        val footerButtons = _find<Button>()
        assertTrue(
            footerButtons.any { it.text == UiTranslations.tr("invoiceDetail.jobs.aiReport.footer.open") },
            "the KI-Volltext footer button must appear when a stored report exists",
        )
    }

    @Test
    fun `ValidationReportDialog footer has no KI-Bericht anzeigen button without a stored report`() {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists(any())).thenReturn(false)

        val dialog = ValidationReportDialog({ key -> UiTranslations.tr(key) }, invoiceDetailQueryPort, documentStore, time = FIXED_TIME_SOURCE)
        dialog.open(job(kositReport = validKositReport(), resultRefs = null))

        val footerButtons = _find<Button>()
        assertFalse(
            footerButtons.any { it.text == UiTranslations.tr("invoiceDetail.jobs.aiReport.footer.open") },
            "no KI-Volltext footer button when there is nothing stored to show",
        )
    }

    @Test
    fun `ValidationReportDialog copy button fires the clipboard JS without throwing`() {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists(any())).thenReturn(false)

        val dialog = ValidationReportDialog({ key -> UiTranslations.tr(key) }, invoiceDetailQueryPort, documentStore, time = FIXED_TIME_SOURCE)
        dialog.open(job(kositReport = validKositReport()))

        val copyButton = _find<Button>().single { it.text == UiTranslations.tr("invoiceDetail.jobs.report.copy") }
        copyButton.click()
    }
}
