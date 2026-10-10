package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._size
import com.github.mvysny.kaributesting.v10.Routes
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.data.provider.hierarchy.AbstractBackEndHierarchicalDataProvider
import com.vaadin.flow.data.provider.hierarchy.HierarchicalQuery
import com.vaadin.flow.router.HasErrorParameter
import de.littletrotter.application.dto.PrintJobDaySummary
import de.littletrotter.application.dto.PrintJobOverviewRow
import de.littletrotter.application.invoice.PrintJobOverviewQueryService
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.ui.component.GridWrapper
import de.littletrotter.shared.DocumentStore
import de.littletrotter.ui.UiEventBus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.OffsetDateTime
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * PO report 30.08.2026 — pins the fix for the "E-Rechnungs-Aufträge" queue view staying on
 * "Weitergeleitet" after the underlying job actually finished: [PrintJobListView] registered
 * neither on [UiEventBus] nor a poll fallback, so nothing in a running UI ever re-queried once a
 * job's status changed elsewhere (the gap [InvoiceDetailView] already had fixed for the
 * per-invoice case). This drives the real view — a real [UiEventBus], attached to a real
 * (mocked-Vaadin) UI — through the fast path production uses: [UiEventBus.publish] dispatches via
 * [UI.access], which the Karibu-testing session runs synchronously, so no `await`/sleep is needed
 * here for the event-bus path (the E2E suite covers the poll-only/cross-process path with an
 * explicit 2-second timeout).
 */
class PrintJobListViewLiveUpdateTest {

    private val printJobOverviewQueryService: PrintJobOverviewQueryService = mock()
    private val contactDataFacade: RecipientQueryPort = mock()
    private val documentStore: DocumentStore = mock()

    // A real bus (not mocked) — the whole point of these tests is the register/publish/unregister
    // wiring itself, not just that some method got called.
    private val uiEventBus = UiEventBus()

    private val day = LocalDate.of(2026, 8, 26)

    @BeforeEach
    fun setup() {
        val testRoutes: MutableSet<Class<out Component>> = mutableSetOf(InvoiceDetailView::class.java, PrintJobListView::class.java)
        val testErrorRoutes: MutableSet<Class<out HasErrorParameter<*>>> = mutableSetOf()
        MockVaadin.setup(Routes(testRoutes, testErrorRoutes, true))
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    /** Publishes on the real bus and drains the UI access queue: the Karibu session holds the lock
     *  while the test runs, so a `UI.access` command only executes once the queue is run. */
    private fun publishJobFinished(jobId: Long) {
        uiEventBus.publish(UiEventBus.JobFinishedEvent(jobId = jobId, invoiceId = 10L, success = true))
        MockVaadin.runUIQueue(true)
    }

    private fun newView(): PrintJobListView =
        PrintJobListView(printJobOverviewQueryService, contactDataFacade, documentStore, uiEventBus, time = FIXED_TIME_SOURCE)

    private inline fun <reified T : Any> privateField(view: PrintJobListView, name: String): T {
        val f = PrintJobListView::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(view).requireAs()
    }

    private fun treeDataProvider(view: PrintJobListView): AbstractBackEndHierarchicalDataProvider<PrintJobTreeNode, Void> =
        privateField(view, "treeDataProvider")

    private fun childQuery(parent: PrintJobTreeNode): HierarchicalQuery<PrintJobTreeNode, Void> =
        HierarchicalQuery(0, 50, emptyList(), null, null, parent)

    private fun statusComponent(view: PrintJobListView, header: String, node: PrintJobTreeNode): Component {
        val grid: com.vaadin.flow.component.grid.Grid<PrintJobTreeNode> = privateField(view, "grid")
        val column = grid.columns.first { it.headerText == header }
        return column.componentFor(node)
    }

    private fun row(status: GenerationJobStatus): PrintJobOverviewRow = PrintJobOverviewRow(
        jobId = 1L,
        invoiceId = 10L,
        invoiceNumber = "RE-2026-00001",
        recipientName = "Acme",
        status = status,
        createdAt = OffsetDateTime.parse("2026-08-26T10:00:00Z"),
        finishedAt = if (status == GenerationJobStatus.COMPLETED) OffsetDateTime.parse("2026-08-26T10:05:00Z") else null,
        attempts = 0,
        lastError = null,
        llmStatus = null,
        pdfObjectKey = null,
        xmlObjectKey = null,
    )

    // The Status column's cell is now a single icon glyph, no visible text at all (see
    // PrintJobListView.buildRowStatusCell); the status word only survives in its own `title`
    // tooltip, which this helper reads instead of a rendered Span's text.
    private fun statusTooltipOf(view: PrintJobListView, node: PrintJobTreeNode.JobNode): String =
        statusComponent(view, "Status", node).element.getAttribute("title")

    private fun fetchTheOnlyJobNode(view: PrintJobListView, dayNode: PrintJobTreeNode.DayNode): PrintJobTreeNode.JobNode =
        treeDataProvider(view).fetchChildren(childQuery(dayNode)).toList().single() as PrintJobTreeNode.JobNode

    @Test
    fun `E2-6 a job-finished event updates the badge but leaves the grid rows and data provider untouched`() {
        val daySummary = PrintJobDaySummary(day, jobCount = 1, failedCount = 0)
        whenever(printJobOverviewQueryService.count(any())).thenReturn(1L)
        whenever(printJobOverviewQueryService.findDaySummaries(any())).thenReturn(listOf(daySummary))
        whenever(printJobOverviewQueryService.findPaged(any(), any(), any())).thenReturn(listOf(row(GenerationJobStatus.DELEGATED)))

        val view = newView()
        val ui = UI.getCurrent()!!
        ui.add(view) // triggers onAttach — registers on uiEventBus, arms the poll fallback
        view.beforeEnter(mock())
        val recipient = Recipient().apply { id = 7L; name = "Acme" }
        privateField<ComboBox<RecipientSummaryDto>>(view, "customerFilter").value = recipient.toRecipientSummaryDto()
        val badge = privateField<PrintJobNewCountBadge>(view, "newCountBadge")
        val grid: com.vaadin.flow.component.treegrid.TreeGrid<PrintJobTreeNode> = privateField(view, "grid")
        val dayNodesBefore: List<PrintJobTreeNode.DayNode> = privateField(view, "dayNodes")
        val gridSizeBefore = grid._size()

        val dayNode = PrintJobTreeNode.DayNode(daySummary)
        assertEquals(GenerationJobStatus.DELEGATED, fetchTheOnlyJobNode(view, dayNode).row.status)

        // The worker finishes the job — same downstream state a real GenerationJobResultConsumer
        // -> GenerationJobService.markCompleted -> DocumentGenerated -> GenerationJobUiPushHandler
        // chain produces, ending in exactly this publish call.
        whenever(printJobOverviewQueryService.findPaged(any(), any(), any())).thenReturn(listOf(row(GenerationJobStatus.COMPLETED)))
        publishJobFinished(1L)

        assertTrue(badge.isVisible, "the event recounts the badge")
        assertEquals(1L, badge.count)
        verify(printJobOverviewQueryService, times(1)).findDaySummaries(any())
        assertSame(dayNodesBefore, privateField<List<PrintJobTreeNode.DayNode>>(view, "dayNodes"))
        assertEquals(gridSizeBefore, grid._size())
        assertTrue(privateField<GridWrapper<*>>(view, "gridWrapper").isVisible)
        assertFalse(privateField<Paragraph>(view, "emptyState").isVisible)
    }

    @Test
    fun `Search after a job finished reloads the grid so the DELEGATED row becomes COMPLETED`() {
        val daySummary = PrintJobDaySummary(day, jobCount = 1, failedCount = 0)
        whenever(printJobOverviewQueryService.count(any())).thenReturn(1L)
        whenever(printJobOverviewQueryService.findDaySummaries(any())).thenReturn(listOf(daySummary))
        whenever(printJobOverviewQueryService.findPaged(any(), any(), any())).thenReturn(listOf(row(GenerationJobStatus.DELEGATED)))

        val view = newView()
        UI.getCurrent()!!.add(view)
        view.beforeEnter(mock())
        val dayNode = PrintJobTreeNode.DayNode(daySummary)
        assertTrue(statusTooltipOf(view, fetchTheOnlyJobNode(view, dayNode)).contains("Weitergeleitet"))

        whenever(printJobOverviewQueryService.findPaged(any(), any(), any())).thenReturn(listOf(row(GenerationJobStatus.COMPLETED)))
        privateField<Button>(view, "searchButton").click()

        val afterNode = fetchTheOnlyJobNode(view, dayNode)
        assertEquals(GenerationJobStatus.COMPLETED, afterNode.row.status)
        assertTrue(statusTooltipOf(view, afterNode).contains("Abgeschlossen"))
    }

    @Test
    fun `detach unregisters from the event bus so a later publish no longer refreshes the view`() {
        whenever(printJobOverviewQueryService.count(any())).thenReturn(0L)
        whenever(printJobOverviewQueryService.findDaySummaries(any())).thenReturn(emptyList())

        val view = newView()
        val ui = UI.getCurrent()!!
        ui.add(view)
        view.beforeEnter(mock())
        privateField<ComboBox<RecipientSummaryDto>>(view, "customerFilter").value = Recipient().apply { id = 7L; name = "Acme" }.toRecipientSummaryDto()
        ui.remove(view) // onDetach — must release the registration

        // Publish again with a filter set. A still-registered listener would recount the badge via
        // the query service's count, which nothing else calls once the view is detached.
        val countCallsBefore = org.mockito.Mockito.mockingDetails(printJobOverviewQueryService)
            .invocations.count { it.method.name == "count" }
        publishJobFinished(1L)

        assertEquals(
            countCallsBefore,
            org.mockito.Mockito.mockingDetails(printJobOverviewQueryService).invocations.count { it.method.name == "count" },
        )
        verify(printJobOverviewQueryService, times(1)).findDaySummaries(any())
    }

    @Test
    fun `onAttach caps the poll interval at 2000ms and onDetach restores whatever the UI had before`() {
        val ui = UI.getCurrent()!!
        ui.pollInterval = 9_000

        val view = newView()
        ui.add(view)
        assertEquals(2_000, ui.pollInterval)

        ui.remove(view)
        assertEquals(9_000, ui.pollInterval)
    }

    @Test
    fun `onAttach leaves an already-fast poll interval untouched`() {
        val ui = UI.getCurrent()!!
        ui.pollInterval = 1_000

        val view = newView()
        ui.add(view)

        assertEquals(1_000, ui.pollInterval)
    }

    // ── fix-print-job-list-blank-rows — see PrintJobTreeNode.DayNode's equality KDoc ──────────

    @Test
    fun `DayNode identity is keyed on the day, not the summary's counts`() {
        val eight = PrintJobTreeNode.DayNode(PrintJobDaySummary(day, jobCount = 8, failedCount = 0))
        val nine = PrintJobTreeNode.DayNode(PrintJobDaySummary(day, jobCount = 9, failedCount = 1))
        val otherDay = PrintJobTreeNode.DayNode(PrintJobDaySummary(day.plusDays(1), jobCount = 8, failedCount = 0))

        assertEquals(eight, nine)
        assertEquals(eight.hashCode(), nine.hashCode())
        assertNotEquals(eight, otherDay)
    }

    @Test
    fun `a job-finished event does not touch the expanded day, Search keeps it expanded and honors a user's collapse`() {
        val eightJobs = PrintJobDaySummary(day, jobCount = 8, failedCount = 0)
        whenever(printJobOverviewQueryService.count(any())).thenReturn(8L)
        whenever(printJobOverviewQueryService.findDaySummaries(any())).thenReturn(listOf(eightJobs))
        whenever(printJobOverviewQueryService.findPaged(any(), any(), any())).thenReturn(listOf(row(GenerationJobStatus.DELEGATED)))

        val view = newView()
        val ui = UI.getCurrent()!!
        ui.add(view)
        view.beforeEnter(mock())

        val grid: com.vaadin.flow.component.treegrid.TreeGrid<PrintJobTreeNode> = privateField(view, "grid")
        assertTrue(grid.isExpanded(PrintJobTreeNode.DayNode(eightJobs)), "most recent day auto-expands on entry")

        // A finished job bumps the day's count in the database, but a push must not reload the grid.
        val nineJobs = PrintJobDaySummary(day, jobCount = 9, failedCount = 0)
        whenever(printJobOverviewQueryService.findDaySummaries(any())).thenReturn(listOf(nineJobs))
        publishJobFinished(1L)
        verify(printJobOverviewQueryService, times(1)).findDaySummaries(any())
        assertTrue(grid.isExpanded(PrintJobTreeNode.DayNode(eightJobs)), "a push leaves the expansion alone")

        // Search reloads: the day stays expanded, and a day the user collapsed stays collapsed.
        val searchButton = privateField<Button>(view, "searchButton")
        searchButton.click()
        assertTrue(grid.isExpanded(PrintJobTreeNode.DayNode(nineJobs)), "expansion survives a Search reload")
        grid.collapse(PrintJobTreeNode.DayNode(nineJobs))
        searchButton.click()
        assertFalse(grid.isExpanded(PrintJobTreeNode.DayNode(nineJobs)), "auto-expand fires per day-change, not per reload")
    }

    @Test
    fun `the single job of the day survives a Search reload after its status changed`() {
        val summary = PrintJobDaySummary(day, jobCount = 1, failedCount = 0)
        whenever(printJobOverviewQueryService.count(any())).thenReturn(1L)
        whenever(printJobOverviewQueryService.findDaySummaries(any())).thenReturn(listOf(summary))
        whenever(printJobOverviewQueryService.findPaged(any(), any(), any())).thenReturn(listOf(row(GenerationJobStatus.DELEGATED)))

        val view = newView()
        UI.getCurrent()!!.add(view)
        view.beforeEnter(mock())

        val grid: com.vaadin.flow.component.treegrid.TreeGrid<PrintJobTreeNode> = privateField(view, "grid")
        assertEquals(2, grid._size(), "Tagesknoten + der eine Auftrag nach dem Eintritt")

        whenever(printJobOverviewQueryService.findPaged(any(), any(), any())).thenReturn(listOf(row(GenerationJobStatus.COMPLETED)))
        privateField<Button>(view, "searchButton").click()

        assertEquals(2, grid._size(), "Tagesknoten + der eine Auftrag bleiben sichtbar")
    }
}
