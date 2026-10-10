package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._get
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasComponents
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.data.renderer.ComponentRenderer
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.GenerationJobQueryService
import de.littletrotter.application.invoice.port.inbound.CurrentDocumentView
import de.littletrotter.application.invoice.port.inbound.DocumentReproductionQueryPort
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.shared.DocumentStore
import de.littletrotter.test.FIXED_TIME_SOURCE
import de.littletrotter.ui.component.DataTable
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class InvoiceGenerationJobsPanelReproductionTagKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    /** The reproduction tag is the only warning-toned symbol the panel renders. */
    private fun isTag(span: Span): Boolean = span.classNames.contains("status-glyph--warn")

    private fun flatten(component: Component): List<Component> =
        listOf(component) + (component as? HasComponents)
            ?.let { component.children.toList().flatMap { child -> flatten(child) } }
            .orEmpty()

    private fun job(jobId: Long) = GenerationJob().apply {
        id = jobId
        status = GenerationJobStatus.COMPLETED
        invoice = Invoice().apply { id = 1L }
        createdAt = OffsetDateTime.of(2026, 9, 30, 10, 0, 0, 0, ZoneOffset.UTC).plusMinutes(jobId)
    }

    private fun document(jobId: Long?, reproduced: Boolean) = CurrentDocumentView(
        objectKey = "k", jobId = jobId, reproduced = reproduced,
        reproducedAt = OffsetDateTime.of(2026, 9, 30, 10, 5, 0, 0, ZoneOffset.UTC),
        reproductionOfSha256 = null,
    )

    private fun panel(port: DocumentReproductionQueryPort?): InvoiceGenerationJobsPanel {
        val jobsService: GenerationJobQueryService = mock()
        whenever(jobsService.findByInvoiceId(any())).thenReturn(listOf(job(1L), job(2L)))
        val detail: InvoiceDetailQueryPort = mock()
        whenever(detail.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())
        return InvoiceGenerationJobsPanel(
            generationJobQueryService = jobsService,
            invoiceDetailQueryPort = detail,
            documentStore = mock<DocumentStore>(),
            time = FIXED_TIME_SOURCE,
            documentReproductionQueryPort = port,
        ).also {
            it.render(
                InvoiceDetailDto(
                    id = 1L, invoiceNumber = "INV-1", status = 2, invoiceDate = null,
                    netAmount = null, vatAmount = null, grossAmount = null,
                    sender = null, recipient = null, items = emptyList(), latestGenerationJob = null,
                ),
            )
        }
    }

    private fun statusCellTags(panel: InvoiceGenerationJobsPanel, rowIndex: Int): List<Span> {
        val grid = flatten(panel).filterIsInstance<DataTable<*>>().single()
        val item = grid._get(rowIndex)
        val renderer = grid.columns[2].renderer.requireAs<ComponentRenderer<Component, GenerationJob>>()
        return flatten(renderer.createComponent(item.requireAs<GenerationJob>())).filterIsInstance<Span>().filter(::isTag)
    }

    private fun portReturning(view: CurrentDocumentView?): DocumentReproductionQueryPort =
        mock<DocumentReproductionQueryPort>().also { whenever(it.currentDocument(1L)).thenReturn(view) }

    @Test
    fun `the job behind the reproduced document carries the tag with a dated tooltip`() {
        val panel = panel(portReturning(document(jobId = 2L, reproduced = true)))

        val tags = statusCellTags(panel, rowIndex = 1) + statusCellTags(panel, rowIndex = 0)

        assertEquals(1, tags.size, "exactly one job is the reproduction")
        val title = tags.single().element.getAttribute("title")
        assertTrue(title.contains("30.09.2026"), "tooltip names the reproduction date: $title")
        assertEquals(title, tags.single().element.getAttribute("aria-label"), "the symbol is named by its tooltip")
    }

    @Test
    fun `the latest-job summary shows the tag when the latest job is the reproduction`() {
        val panel = panel(portReturning(document(jobId = 2L, reproduced = true)))

        val summaryTags = flatten(panel).filterIsInstance<Span>().filter { isTag(it) && it.element.getAttribute("title") != null }

        assertTrue(summaryTags.isNotEmpty(), "latest job 2 is the reproduction")
    }

    @Test
    fun `no tag when the current document is not reproduced`() {
        val panel = panel(portReturning(document(jobId = 2L, reproduced = false)))

        assertTrue(statusCellTags(panel, 0).isEmpty() && statusCellTags(panel, 1).isEmpty())
        assertTrue(flatten(panel).filterIsInstance<Span>().none(::isTag))
    }

    @Test
    fun `no tag when there is no current document or no port`() {
        assertTrue(flatten(panel(portReturning(null))).filterIsInstance<Span>().none(::isTag))
        assertTrue(flatten(panel(null)).filterIsInstance<Span>().none(::isTag))
    }

    @Test
    fun `a reproduction without a timestamp still shows the tag and a placeholder date`() {
        val view = document(jobId = 1L, reproduced = true).copy(reproducedAt = null)
        val panel = panel(portReturning(view))

        val tag = statusCellTags(panel, rowIndex = 0).single()

        assertNotNull(tag.element.getAttribute("title"))
    }

    @Test
    fun `the current document is loaded once per render, not per row`() {
        val port = portReturning(document(jobId = 2L, reproduced = true))
        val panel = panel(port)
        statusCellTags(panel, 0)
        statusCellTags(panel, 1)

        verify(port, times(1)).currentDocument(1L)
    }
}
