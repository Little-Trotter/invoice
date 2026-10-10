package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._get
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasComponents
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.html.Span
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.GenerationJobQueryService
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.shared.DocumentStore
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
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
 * fix-job-panel-flicker-and-vanishing-entry (F1/F2) — pins the two structural invariants the fix
 * introduces to [InvoiceGenerationJobsPanel]'s job section:
 *  - F1: [InvoiceGenerationJobsPanel.refresh] never rebuilds the job Grid — the same [DataTable]
 *    instance keeps rendering across every refresh (Grid identity survives; in the real browser so
 *    does scroll position/selection, which this test cannot observe directly, but Grid identity is
 *    exactly the precondition for that).
 *  - F2: an empty read must never destroy a Grid that already held rows — the empty state is a
 *    visibility toggle over the very same `jobs` list the Grid renders, not a structural branch
 *    that swaps the Grid out for a `Paragraph`.
 *
 * The job Grid itself was relocated from the former monolithic `InvoiceExportPanel` into
 * [InvoiceGenerationJobsPanel], which now owns it.
 */
class InvoiceGenerationJobsPanelRefreshKaribuTest {

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

    private fun job(id: Long, status: GenerationJobStatus = GenerationJobStatus.COMPLETED): GenerationJob =
        GenerationJob().apply {
            this.id = id
            this.status = status
            this.invoice = Invoice().apply { this.id = 1L }
            this.createdAt = OffsetDateTime.now()
        }

    private fun buildPanel(generationJobQueryService: GenerationJobQueryService): InvoiceGenerationJobsPanel {
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists(any())).thenReturn(false)
        return InvoiceGenerationJobsPanel(
            generationJobQueryService = generationJobQueryService,
            invoiceDetailQueryPort = invoiceDetailQueryPort,
            documentStore = documentStore, time = FIXED_TIME_SOURCE,
        )
    }

    private fun grid(panel: InvoiceGenerationJobsPanel): DataTable<*>? =
        flatten(panel).filterIsInstance<DataTable<*>>().singleOrNull()

    private fun emptyStateParagraph(panel: InvoiceGenerationJobsPanel): Paragraph? =
        flatten(panel).filterIsInstance<Paragraph>().singleOrNull { it.text == UiTranslations.tr("invoiceDetail.jobs.empty") }

    @Test
    fun `F1 the job Grid instance is the same object before and after a refresh`() {
        val generationJobQueryService: GenerationJobQueryService = mock()
        whenever(generationJobQueryService.findByInvoiceId(any())).thenReturn(listOf(job(1L)))
        val panel = buildPanel(generationJobQueryService)

        panel.render(invoiceDetailDto())
        val gridBefore = grid(panel)
        assertTrue(gridBefore != null, "the Grid must exist once jobs are present")

        whenever(generationJobQueryService.findByInvoiceId(any())).thenReturn(listOf(job(1L), job(2L)))
        panel.refresh(invoiceDetailDto())
        val gridAfter = grid(panel)

        assertSame(gridBefore, gridAfter, "F1 — a refresh must swap the Grid's data, not rebuild the Grid itself")
    }

    @Test
    fun `F2 a refresh whose read returns an empty list does not remove a Grid that previously held rows`() {
        val generationJobQueryService: GenerationJobQueryService = mock()
        whenever(generationJobQueryService.findByInvoiceId(any())).thenReturn(listOf(job(1L)))
        val panel = buildPanel(generationJobQueryService)

        panel.render(invoiceDetailDto())
        val gridBefore = grid(panel)
        assertTrue(gridBefore != null)

        // The exact "bad read" F2 pins: a refresh whose own read happens to be empty (a raced or
        // stale query, not necessarily reality) must not tear the Grid out of the tree.
        whenever(generationJobQueryService.findByInvoiceId(any())).thenReturn(emptyList())
        panel.refresh(invoiceDetailDto())

        val gridAfter = grid(panel)
        assertSame(gridBefore, gridAfter, "the Grid must still be the very same instance, merely hidden")
        assertFalse(gridAfter!!.isVisible, "an empty read hides the Grid...")
        val empty = emptyStateParagraph(panel)
        assertTrue(empty != null && empty.isVisible, "...and shows the empty-state paragraph instead")
    }

    /** Same idiom [InvoiceDetailViewItemGridColumnsKaribuTest] already established: the status
     *  column's cell is a composed [com.vaadin.flow.component.orderedlayout.HorizontalLayout], so
     *  karibu-testing's own `_getFormattedRow` (which calls `Component.toString()`) never reaches
     *  the nested [de.littletrotter.ui.component.StatusChip]'s text — creating the renderer's
     *  component directly and flattening it does. */
    private fun statusColumnText(panel: InvoiceGenerationJobsPanel, rowIndex: Int): String {
        val gridInstance = grid(panel)!!
        // _get goes through the Grid's REAL data provider/DataCommunicator fetch pipeline — this
        // is what proves the provider's backing list, not just an arbitrary object, actually
        // carries the job's current status after a refresh.
        val item = gridInstance._get(rowIndex).requireAs<GenerationJob>()
        val statusColumn = gridInstance.columns[2] // id, type, status — see buildJobsGrid's column order
        val cell = statusColumn.renderer.requireAs<com.vaadin.flow.data.renderer.ComponentRenderer<Component, GenerationJob>>()
            .createComponent(item)
        return flatten(cell).filterIsInstance<Span>().joinToString(" ") { it.element.getAttribute("aria-label") ?: "" }
    }

    /**
     * L24 (fix-l24-draft-job-status-never-pushed) — pins that a refresh (whichever mechanism
     * triggers it: push or the poll fallback, see [InvoiceGenerationJobsPanel.refresh]'s own KDoc)
     * actually shows the job's NEW status for the SAME job id, not just that the Grid instance
     * survives (F1) or stays visible (F2). A refresh that left the data provider's backing item
     * stale (by reference or otherwise) would exactly reproduce the reported symptom: the row
     * keeps saying "Weitergeleitet" after the job actually reached COMPLETED.
     */
    @Test
    fun `L24 a refresh with the same job id now COMPLETED updates the rendered status symbol`() {
        val generationJobQueryService: GenerationJobQueryService = mock()
        whenever(generationJobQueryService.findByInvoiceId(any())).thenReturn(listOf(job(1L, GenerationJobStatus.DELEGATED)))
        val panel = buildPanel(generationJobQueryService)

        panel.render(invoiceDetailDto())
        val textBefore = statusColumnText(panel, 0)
        assertTrue(
            textBefore.contains(UiTranslations.tr("invoiceDetail.jobs.status.delegated")),
            "the initial render must show the DELEGATED status: '$textBefore'",
        )

        whenever(generationJobQueryService.findByInvoiceId(any())).thenReturn(listOf(job(1L, GenerationJobStatus.COMPLETED)))
        panel.refresh(invoiceDetailDto())

        val textAfter = statusColumnText(panel, 0)
        assertTrue(
            textAfter.contains(UiTranslations.tr("invoiceDetail.jobs.status.completed")),
            "a refresh must show the job's CURRENT status, COMPLETED, not the stale DELEGATED one: '$textAfter'",
        )
    }

    @Test
    fun `the empty to first-entry transition shows the entry and keeps showing it across a second refresh`() {
        val generationJobQueryService: GenerationJobQueryService = mock()
        whenever(generationJobQueryService.findByInvoiceId(any())).thenReturn(emptyList())
        val panel = buildPanel(generationJobQueryService)

        panel.render(invoiceDetailDto())
        val emptyBefore = emptyStateParagraph(panel)
        assertTrue(emptyBefore != null && emptyBefore.isVisible, "starts on the empty state")
        assertTrue(grid(panel)?.isVisible != true, "no Grid rows to show yet")

        // The PO's exact repro: the invoice had no job before, then the first one is scheduled.
        whenever(generationJobQueryService.findByInvoiceId(any())).thenReturn(listOf(job(1L)))
        panel.refresh(invoiceDetailDto())

        val gridAfterFirstEntry = grid(panel)
        assertTrue(gridAfterFirstEntry != null && gridAfterFirstEntry.isVisible, "the first entry must show up")
        assertFalse(emptyBefore!!.isVisible, "the empty-state paragraph must hide once a row exists")

        // ... and it must still be there on a SECOND refresh — this is exactly what the reported
        // bug broke: the entry showed up once and vanished again on the very next refresh.
        panel.refresh(invoiceDetailDto())
        val gridAfterSecondRefresh = grid(panel)
        assertSame(gridAfterFirstEntry, gridAfterSecondRefresh, "F1 — still the same Grid instance")
        assertTrue(gridAfterSecondRefresh!!.isVisible, "F2 — still visible, the entry did not vanish again")
    }

    /** The download column's cell is rebuilt from scratch on every [InvoiceGenerationJobsPanel
     *  .refresh] (its `ComponentRenderer` closure runs again, same as [statusColumnText]'s own
     *  KDoc describes for the status column); this pins that a freshly rebuilt cell still carries
     *  the identical, stable `/documents/jobs/{jobId}/pdf` URL rather than a per-attach Vaadin
     *  dynamic resource — the root cause finding this package fixes. */
    private fun downloadAnchorHref(panel: InvoiceGenerationJobsPanel, rowIndex: Int): String {
        val gridInstance = grid(panel)!!
        val item = gridInstance._get(rowIndex).requireAs<GenerationJob>()
        val downloadColumn = gridInstance.columns.last() // buildJobsGrid: download is the last, frozen-to-end column
        val cell = downloadColumn.renderer.requireAs<com.vaadin.flow.data.renderer.ComponentRenderer<Component, GenerationJob>>()
            .createComponent(item)
        return flatten(cell).filterIsInstance<com.vaadin.flow.component.html.Anchor>().first().href
    }

    @Test
    fun `ST-A5 the download anchor's URL is identical before and after a refresh and is never a Vaadin dynamic resource`() {
        val generationJobQueryService: GenerationJobQueryService = mock()
        val completedJob = job(1L, GenerationJobStatus.COMPLETED).apply {
            resultRefs = """{"objectKey":"invoices/re-1.pdf"}"""
        }
        whenever(generationJobQueryService.findByInvoiceId(any())).thenReturn(listOf(completedJob))
        val panel = buildPanel(generationJobQueryService)
        panel.render(invoiceDetailDto())

        val hrefBefore = downloadAnchorHref(panel, 0)
        assertEquals("documents/jobs/1/pdf", hrefBefore)

        // Simulate a live-update refresh (push event / poll fallback) with the same, still-
        // completed job — exactly the scenario that used to kill a DownloadHandler-backed URL.
        whenever(generationJobQueryService.findByInvoiceId(any())).thenReturn(listOf(completedJob))
        panel.refresh(invoiceDetailDto())

        val hrefAfter = downloadAnchorHref(panel, 0)
        assertEquals(hrefBefore, hrefAfter, "the anchor's URL must not change across a refresh")
        assertFalse(hrefAfter.startsWith("VAADIN/dynamic/resource"), "must never be a per-attach Vaadin dynamic resource")
    }
}
