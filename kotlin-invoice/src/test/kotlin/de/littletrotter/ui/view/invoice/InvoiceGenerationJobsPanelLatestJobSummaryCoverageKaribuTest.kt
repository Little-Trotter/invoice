package de.littletrotter.ui.view.invoice

import de.littletrotter.testing.jobView
import de.littletrotter.application.invoice.port.inbound.GenerationJobView
import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasComponents
import com.vaadin.flow.component.html.NativeButton
import com.vaadin.flow.component.html.Paragraph
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.port.inbound.GenerationJobQueryPort
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.fixture.edit
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.ui.i18n.UiTranslations
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
 * UI-5 slice 3a — [InvoiceGenerationJobsPanel.buildLatestJobSummary] and its private
 * `buildValidationReasonLine`: the `newJob`/`latestJob` id placeholder, the advisory-AI-chip
 * `?.let` (present/absent), and the reason line itself — only reachable when the most recent job
 * is [de.littletrotter.ui.view.invoice.ValidationOutcome.NotSuccessful], which no existing test
 * arranges (every fixture elsewhere renders a COMPLETED-with-valid-report latest job). Relocated
 * from the former monolithic `InvoiceExportPanel` into [InvoiceGenerationJobsPanel],
 * which now owns the job Grid and the latest-job summary.
 */
class InvoiceGenerationJobsPanelLatestJobSummaryCoverageKaribuTest {

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

    private fun invoiceDetailDto(id: Long = 1L): InvoiceDetailDto = InvoiceDetailDto(
        id = id, invoiceNumber = "INV-1", status = 0, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = null, items = emptyList(), latestGenerationJob = null,
    )

    private fun job(
        id: Long? = 1L,
        status: GenerationJobStatus = GenerationJobStatus.COMPLETED,
        kositReport: String? = null,
        resultRefs: String? = null,
    ): GenerationJobView = jobView {
        this.id = id
        this.status = status
        this.kositReport = kositReport
        this.resultRefs = resultRefs
        this.invoice = Invoice().edit { this.id = 1L }
        this.createdAt = OffsetDateTime.now()
    }

    private fun validKositReport(): String = KositReportPayload.encode(KositValidationResult(status = KositOutcome.VALID))

    private fun buildPanel(
        theJob: GenerationJobView,
        documentStore: DocumentStore = mock<DocumentStore>().also { whenever(it.exists(any())).thenReturn(false) },
    ): InvoiceGenerationJobsPanel {
        val generationJobQueryPort: GenerationJobQueryPort = mock()
        whenever(generationJobQueryPort.findJobViewsByInvoiceId(any())).thenReturn(listOf(theJob))
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())

        val panel = InvoiceGenerationJobsPanel(
            generationJobQueryPort = generationJobQueryPort,
            invoiceDetailQueryPort = invoiceDetailQueryPort,
            documentStore = documentStore, time = FIXED_TIME_SOURCE,
        )
        panel.render(invoiceDetailDto())
        return panel
    }

    // ── the "latest" paragraph: newJob.id ?: "n/a" ──────────────────────────────────────────────

    @Test
    fun `the latest-job paragraph shows n-a when the job has no id yet`() {
        val panel = buildPanel(job(id = null, kositReport = validKositReport()))

        val paragraphs = flatten(panel).filterIsInstance<Paragraph>().map { it.text }
        assertTrue(
            paragraphs.contains(UiTranslations.tr("invoiceDetail.jobs.latest", "n/a")),
            "expected the n/a placeholder, got: $paragraphs",
        )
    }

    @Test
    fun `the latest-job paragraph shows the real id when present`() {
        val panel = buildPanel(job(id = 77L, kositReport = validKositReport()))

        val paragraphs = flatten(panel).filterIsInstance<Paragraph>().map { it.text }
        assertTrue(
            paragraphs.contains(UiTranslations.tr("invoiceDetail.jobs.latest", "77")),
            "expected the real-id paragraph, got: $paragraphs",
        )
    }

    // ── the advisory AI chip: present / absent ──────────────────────────────────────────────────

    @Test
    fun `no advisory AI chip is shown when there is nothing advisory for the latest job`() {
        val panel = buildPanel(job(kositReport = validKositReport(), resultRefs = null))

        val chips = flatten(panel).filterIsInstance<NativeButton>().filter { it.classNames.contains("ai-advisory-chip") }
        assertTrue(chips.isEmpty(), "no stored report and no analyses -> no advisory chip")
    }

    @Test
    fun `the advisory AI chip is shown when a stored LLM report exists for the latest job`() {
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists("reports/llm-1.md")).thenReturn(true)
        val panel = buildPanel(
            job(kositReport = validKositReport(), resultRefs = """{"llmReportKey":"reports/llm-1.md"}"""),
            documentStore = documentStore,
        )

        val chips = flatten(panel).filterIsInstance<NativeButton>().filter { it.classNames.contains("ai-advisory-chip") }
        assertEquals(1, chips.size, "a stored report must surface exactly one advisory chip")
    }

    // ── the reason line: not shown for a successful outcome, shown (with/without detail) for a
    //    failed one ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `no reason line is shown when the latest job succeeded`() {
        val panel = buildPanel(job(status = GenerationJobStatus.COMPLETED, kositReport = validKositReport()))

        val reasonText = UiTranslations.tr("validation.reason.aborted")
        val paragraphs = flatten(panel).filterIsInstance<Component>().mapNotNull {
            (it as? com.vaadin.flow.component.html.Span)?.text
        }
        assertFalse(paragraphs.any { it.startsWith(reasonText) })
    }

    @Test
    fun `the reason line shows the plain reason text when the failure carries no detail`() {
        val panel = buildPanel(job(status = GenerationJobStatus.FAILED, resultRefs = null))

        val spans = flatten(panel).filterIsInstance<com.vaadin.flow.component.html.Span>().map { it.text }
        assertTrue(
            spans.contains(UiTranslations.tr("validation.reason.aborted")),
            "expected the bare reason text (no detail to append), got: $spans",
        )
    }

    @Test
    fun `the reason line appends the detail after a colon when the failure carries one`() {
        val panel = buildPanel(job(status = GenerationJobStatus.FAILED, resultRefs = """{"error":"timeout talking to KoSIT"}"""))

        val spans = flatten(panel).filterIsInstance<com.vaadin.flow.component.html.Span>().map { it.text }
        val expected = "${UiTranslations.tr("validation.reason.aborted")}: timeout talking to KoSIT"
        assertTrue(spans.contains(expected), "expected the reason text with its detail appended, got: $spans")
    }
}
