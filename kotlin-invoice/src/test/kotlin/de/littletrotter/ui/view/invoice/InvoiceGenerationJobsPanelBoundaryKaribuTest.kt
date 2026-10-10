package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasComponents
import com.vaadin.flow.component.html.Span
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.invoice.GenerationJobQueryService
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositReportPayload
import de.littletrotter.shared.KositValidationResult
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
 * [InvoiceGenerationJobsPanel] edge path: the latest job's format label,
 * relocated from the former monolithic `InvoiceExportPanel`; the other edge paths of the original
 * `InvoiceExportPanelBoundaryKaribuTest` (the print button's own refresh/immutable/template-scope
 * boundaries) now live in [InvoicePrintControlsBoundaryKaribuTest].
 */
class InvoiceGenerationJobsPanelBoundaryKaribuTest {

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

    private fun invoice(): InvoiceDetailDto = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = 0, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = PartyDto(id = 2L, name = "Seller"),
        recipient = PartyDto(id = 3L, name = "Buyer", customerNumber = "KD-1"),
        items = emptyList(), latestGenerationJob = null,
    )

    private fun job(profile: String?): GenerationJob = GenerationJob().apply {
        id = 5L
        status = GenerationJobStatus.COMPLETED
        kositReport = KositReportPayload.encode(KositValidationResult(status = KositOutcome.VALID, profile = profile))
        invoice = Invoice().apply { id = 1L }
        createdAt = OffsetDateTime.now()
    }

    private fun panel(jobs: List<GenerationJob> = emptyList()): InvoiceGenerationJobsPanel {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())
        val generationJobQueryService: GenerationJobQueryService = mock()
        whenever(generationJobQueryService.findByInvoiceId(any())).thenReturn(jobs)
        return InvoiceGenerationJobsPanel(
            generationJobQueryService = generationJobQueryService,
            invoiceDetailQueryPort = invoiceDetailQueryPort,
            documentStore = mock<DocumentStore>(), time = FIXED_TIME_SOURCE,
        )
    }

    @Test
    fun `happy path - the latest job summary names the validated export profile`() {
        val panel = panel(jobs = listOf(job(profile = "XRechnung")))
        panel.render(invoice())

        assertTrue(flatten(panel).filterIsInstance<Span>().any { it.text == "XRechnung" })
    }

    @Test
    fun `boundary - a job without a known format shows no format line`() {
        val panel = panel(jobs = listOf(job(profile = null)))
        panel.render(invoice())

        assertTrue(flatten(panel).filterIsInstance<Span>().none { it.text == "XRechnung" })
    }
}
