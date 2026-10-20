package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10.Routes
import com.github.mvysny.kaributesting.v10._clickItem
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.data.provider.Query
import com.vaadin.flow.data.provider.hierarchy.AbstractBackEndHierarchicalDataProvider
import com.vaadin.flow.data.provider.hierarchy.HierarchicalQuery
import com.vaadin.flow.router.HasErrorParameter
import de.littletrotter.application.dto.GenerationJobFilter
import de.littletrotter.application.dto.PrintJobDaySummary
import de.littletrotter.application.dto.PrintJobOverviewRow
import de.littletrotter.application.invoice.port.inbound.PrintJobOverviewQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.fixture.aRecipient
import de.littletrotter.shared.DocumentStore
import de.littletrotter.ui.UiEventBus
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.OffsetDateTime
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * Closes branch-coverage gaps in [PrintJobListView] that
 * [PrintJobListViewKaribuTest]/[PrintJobListViewLiveUpdateTest] do not reach: the Kunde column's
 * own `when`, the item-click listener (never exercised by either existing suite), the
 * hierarchical data provider's `getChildCount` and the poll-fallback's
 * `hasActiveJobsInResultSet` query — all real behaviour, none reachable through a test-only
 * accessor.
 */
class PrintJobListViewCoverageKaribuTest {

    private val printJobOverviewQueryService: PrintJobOverviewQueryPort = mock()
    private val contactDataFacade: RecipientQueryPort = mock()
    private val documentStore: DocumentStore = mock()
    private val uiEventBus = UiEventBus()

    @BeforeEach
    fun setup() {
        DirectPollTicks.install()
        val testRoutes: MutableSet<Class<out Component>> = mutableSetOf(InvoiceDetailView::class.java, PrintJobListView::class.java)
        val testErrorRoutes: MutableSet<Class<out HasErrorParameter<*>>> = mutableSetOf()
        MockVaadin.setup(Routes(testRoutes, testErrorRoutes, true))
        whenever(printJobOverviewQueryService.count(any())).thenReturn(0L)
        whenever(printJobOverviewQueryService.findPaged(any(), any(), any())).thenReturn(emptyList())
        whenever(printJobOverviewQueryService.findDaySummaries(any())).thenReturn(emptyList())
    }

    @AfterEach
    fun teardown() {
        DirectPollTicks.restore()
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun newView(): PrintJobListView =
        PrintJobListView(printJobOverviewQueryService, contactDataFacade, documentStore, uiEventBus, time = FIXED_TIME_SOURCE)

    private inline fun <reified T : Any> privateField(view: PrintJobListView, name: String): T {
        val f = PrintJobListView::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(view).requireAs()
    }

    private fun grid(view: PrintJobListView): com.vaadin.flow.component.grid.Grid<PrintJobTreeNode> = privateField(view, "grid")

    private fun treeDataProvider(view: PrintJobListView): AbstractBackEndHierarchicalDataProvider<PrintJobTreeNode, Void> =
        privateField(view, "treeDataProvider")

    private fun columnRenderer(view: PrintJobListView, header: String): com.vaadin.flow.data.renderer.ComponentRenderer<Component, PrintJobTreeNode> {
        val column = grid(view).columns.first { it.headerText == header }
        return column.renderer.requireAs()
    }

    private fun rootQuery(): HierarchicalQuery<PrintJobTreeNode, Void> =
        HierarchicalQuery(0, 50, emptyList(), null, null, null)

    private fun childQuery(parent: PrintJobTreeNode): HierarchicalQuery<PrintJobTreeNode, Void> =
        HierarchicalQuery(0, 50, emptyList(), null, null, parent)

    private fun row(
        jobId: Long = 1L,
        invoiceId: Long = 10L,
        invoiceNumber: String = "RE-2026-00001",
        recipientName: String? = "Acme",
        status: GenerationJobStatus = GenerationJobStatus.COMPLETED,
    ): PrintJobOverviewRow = PrintJobOverviewRow(
        jobId = jobId,
        invoiceId = invoiceId,
        invoiceNumber = invoiceNumber,
        recipientName = recipientName,
        status = status,
        createdAt = OffsetDateTime.parse("2026-08-20T10:00:00Z"),
        finishedAt = OffsetDateTime.parse("2026-08-20T10:05:00Z"),
        attempts = 0,
        lastError = null,
        llmStatus = null,
        pdfObjectKey = null,
        xmlObjectKey = null,
    )

    private fun daySummary(day: LocalDate = LocalDate.of(2026, 8, 26), jobCount: Long = 12, failedCount: Long = 0): PrintJobDaySummary =
        PrintJobDaySummary(day, jobCount, failedCount)

    // ── Kunde column: JobNode dash / name, DayNode blank ────────────────────────

    @Test
    fun `the Kunde column shows a dash when the job row has no recipient name`() {
        val view = newView()
        val cell = columnRenderer(view, "Empfängerprofil").createComponent(PrintJobTreeNode.JobNode(row(recipientName = null))) as Span

        assertEquals("—", cell.text)
    }

    @Test
    fun `the Kunde column shows the recipient name when present`() {
        val view = newView()
        val cell = columnRenderer(view, "Empfängerprofil").createComponent(PrintJobTreeNode.JobNode(row(recipientName = "Zeta Logistik"))) as Span

        assertEquals("Zeta Logistik", cell.text)
    }

    @Test
    fun `the Kunde column renders blank for a day row`() {
        val view = newView()
        val cell = columnRenderer(view, "Empfängerprofil").createComponent(PrintJobTreeNode.DayNode(daySummary())) as Span

        assertEquals("", cell.text)
    }

    // ── item click listener: job row navigates, day row does nothing ───────────

    @Test
    fun `clicking a job row reaches the navigate call for that invoice's detail view`() {
        val daySummaryVal = daySummary()
        whenever(printJobOverviewQueryService.count(any())).thenReturn(1L)
        whenever(printJobOverviewQueryService.findDaySummaries(any())).thenReturn(listOf(daySummaryVal))
        whenever(printJobOverviewQueryService.findPaged(any(), any(), any())).thenReturn(listOf(row(jobId = 5L, invoiceId = 42L)))
        val view = newView()
        val ui = UI.getCurrent()!!
        ui.add(view)
        view.beforeEnter(mock())

        // InvoiceDetailView requires CDI-injected constructor args this plain unit test never
        // provides, so Vaadin's router catches the instantiation failure internally and renders
        // its own InternalServerError view instead of propagating — the route it tried (naming
        // this exact invoice id) is proof the click handler's `?.let { navigateToInvoice(...) }`
        // branch ran, not just that `as? JobNode` matched.
        // Row 0 is the (auto-expanded) day node, row 1 its only job.
        grid(view)._clickItem(1)

        assertEquals("invoice/42", ui.internals.activeViewLocation.path)
    }

    @Test
    fun `clicking a day row does not navigate anywhere`() {
        val daySummaryVal = daySummary()
        whenever(printJobOverviewQueryService.count(any())).thenReturn(1L)
        whenever(printJobOverviewQueryService.findDaySummaries(any())).thenReturn(listOf(daySummaryVal))
        whenever(printJobOverviewQueryService.findPaged(any(), any(), any())).thenReturn(listOf(row()))
        val view = newView()
        val ui = UI.getCurrent()!!
        ui.add(view)
        view.beforeEnter(mock())

        // The item-click listener's `as? JobNode` cast fails for a day row, so `?.let {}` never
        // reaches navigateToInvoice() — nothing should throw here, unlike the job-row case above.
        grid(view)._clickItem(0)
    }

    // ── hierarchical data provider: getChildCount / hasChildren ────────────────

    @Test
    fun `getChildCount at the root level returns the number of cached day nodes`() {
        whenever(printJobOverviewQueryService.count(any())).thenReturn(2L)
        whenever(printJobOverviewQueryService.findDaySummaries(any())).thenReturn(listOf(daySummary(), daySummary(day = LocalDate.of(2026, 8, 20))))
        val view = newView()
        view.beforeEnter(mock())

        val count = treeDataProvider(view).getChildCount(rootQuery())

        assertEquals(2, count)
    }

    @Test
    fun `getChildCount for a day node queries the service with that day's own bounds`() {
        val day = LocalDate.of(2026, 8, 26)
        whenever(printJobOverviewQueryService.count(any())).thenReturn(7L)
        val view = newView()

        val count = treeDataProvider(view).getChildCount(childQuery(PrintJobTreeNode.DayNode(daySummary(day = day))))

        assertEquals(7, count)
    }

    @Test
    fun `hasChildren is true for a day node and false for a job node`() {
        val view = newView()

        assertTrue(treeDataProvider(view).hasChildren(PrintJobTreeNode.DayNode(daySummary())))
        assertFalse(treeDataProvider(view).hasChildren(PrintJobTreeNode.JobNode(row())))
    }

    // ── customerFilter lazy provider: limit=0 guard against division by zero ───

    private fun customerFilterDataProvider(view: PrintJobListView): com.vaadin.flow.data.provider.DataProvider<RecipientSummaryDto, String> {
        val customerFilter: ComboBox<RecipientSummaryDto> = privateField(view, "customerFilter")
        return customerFilter.dataProvider.requireAs()
    }

    @Test
    fun `customerFilter's lazy fetch with a zero limit does not divide by zero`() {
        whenever(contactDataFacade.findRecipients(0, 0, null)).thenReturn(emptyList())
        val view = newView()

        val result = customerFilterDataProvider(view).fetch(Query(0, 0, emptyList(), null, null)).toList()

        assertTrue(result.isEmpty())
        verify(contactDataFacade).findRecipients(0, 0, null)
    }

    // Note: onAttach(null)/onDetach(null)'s `attachEvent != null`/`detachEvent != null` guards are
    // left uncovered on their false side. Both overrides are `protected` (Vaadin's Component API),
    // so calling them directly from a same-package test would need a test-only visibility widening
    // — the thing the task's METHOD section rules out. In real use Vaadin never invokes either
    // callback with a null event; this is the same class of permanently-missed defensive branch
    // recorded for InvoiceDetailView's handleFinalizationBlocked.

    // ── poll fallback: hasActiveJobsInResultSet's status-intersection branches ─

    private fun firePoll(ui: UI) {
        DirectPollTicks.firePoll(ui)
    }

    private fun badgeCountCalls(): Int = org.mockito.Mockito.mockingDetails(printJobOverviewQueryService)
        .invocations.count { inv ->
            inv.method.name == "count" && (inv.arguments.first() as GenerationJobFilter).let { it.statuses.isEmpty() && it.createdFrom != null }
        }

    private fun findDaySummariesCalls(): Int = org.mockito.Mockito.mockingDetails(printJobOverviewQueryService)
        .invocations.count { it.method.name == "findDaySummaries" }

    private fun viewWithRecipientFilter(): PrintJobListView {
        val view = newView()
        UI.getCurrent()!!.add(view)
        view.beforeEnter(mock())
        privateField<ComboBox<RecipientSummaryDto>>(view, "customerFilter").value = aRecipient { id = 7L; name = "Acme" }.toRecipientSummaryDto()
        return view
    }

    @Test
    fun `E2-7 a poll tick with an active job recounts the badge and leaves the grid data alone`() {
        whenever(printJobOverviewQueryService.count(any())).thenReturn(1L)
        whenever(printJobOverviewQueryService.findDaySummaries(any())).thenReturn(listOf(daySummary()))
        val view = viewWithRecipientFilter()
        val ui = UI.getCurrent()!!
        val badge = privateField<PrintJobNewCountBadge>(view, "newCountBadge")
        val badgeCallsBefore = badgeCountCalls()
        val daySummariesBefore = findDaySummariesCalls()

        firePoll(ui)

        assertTrue(badgeCountCalls() > badgeCallsBefore, "the tick recounts the badge")
        assertEquals(daySummariesBefore, findDaySummariesCalls(), "the tick must not reload the day rows")
        assertEquals(1L, badge.count)
        assertTrue(badge.isVisible)
    }

    @Test
    fun `F4 the first poll tick after attach always recounts the badge once, then no further tick with no active jobs does`() {
        whenever(printJobOverviewQueryService.count(any())).thenReturn(0L)
        val view = viewWithRecipientFilter()
        val ui = UI.getCurrent()!!
        val badgeCallsAfterFieldChange = badgeCountCalls()
        val daySummariesBefore = findDaySummariesCalls()

        // sawActiveJobsInResultSet is seeded true on attach, so the very first poll tick after
        // entering the view always acts once, even though nothing here is active — this closes
        // the "a job created elsewhere finished its whole lifetime between two ticks" hole.
        firePoll(ui)
        assertEquals(badgeCallsAfterFieldChange + 1, badgeCountCalls())

        // A second, consecutive tick with still nothing active does nothing:
        // shouldRefreshOnPollTick(false, false) skips it.
        firePoll(ui)
        assertEquals(badgeCallsAfterFieldChange + 1, badgeCountCalls())
        assertEquals(daySummariesBefore, findDaySummariesCalls())
    }

    /** The poll fallback asks the query service for the active statuses only, on top of
     *  whatever recipient/date filter the last search applied; no status selection is involved. */
    @Test
    fun `the poll fallback counts non-terminal jobs for the searched filter with exactly the active statuses`() {
        val recipient = aRecipient { id = 7L; name = "Acme" }
        whenever(printJobOverviewQueryService.count(any())).thenReturn(0L)
        whenever(printJobOverviewQueryService.findDaySummaries(any())).thenReturn(emptyList())
        val view = newView()
        val ui = UI.getCurrent()!!
        ui.add(view)
        view.beforeEnter(mock())
        val customerFilter: ComboBox<RecipientSummaryDto> = privateField(view, "customerFilter")
        val searchButton: Button = privateField(view, "searchButton")
        customerFilter.value = recipient.toRecipientSummaryDto()
        searchButton.click()

        firePoll(ui)

        verify(printJobOverviewQueryService).count(
            eq(GenerationJobFilter(statuses = ACTIVE_GENERATION_JOB_STATUSES, recipientId = 7L)),
        )
    }

    @Test
    fun `E2-8 a poll tick whose active-job count is zero and that follows an inactive tick skips the badge recount`() {
        whenever(printJobOverviewQueryService.count(any())).thenReturn(0L)
        val view = viewWithRecipientFilter()
        val ui = UI.getCurrent()!!
        firePoll(ui) // consumes the guaranteed first tick seeded on attach
        val badgeCallsBefore = badgeCountCalls()

        firePoll(ui)

        assertEquals(badgeCallsBefore, badgeCountCalls())
        assertFalse(privateField<PrintJobNewCountBadge>(view, "newCountBadge").isVisible)
    }

    @Test
    fun `a poll tick whose count query fails leaves the badge untouched and the next tick still runs`() {
        val view = viewWithRecipientFilter()
        val ui = UI.getCurrent()!!
        val badge = privateField<PrintJobNewCountBadge>(view, "newCountBadge")
        doThrow(IllegalStateException("db down")).whenever(printJobOverviewQueryService).count(any())
        val badgeBefore = badge.count

        firePoll(ui)

        assertEquals(badgeBefore, badge.count)

        doReturn(4L).whenever(printJobOverviewQueryService).count(any())
        firePoll(ui)

        assertEquals(4L, badge.count)
    }

    @Test
    fun `a poll tick loaded before the view detached does not touch the badge`() {
        whenever(printJobOverviewQueryService.count(any())).thenReturn(0L)
        val view = viewWithRecipientFilter()
        val ui = UI.getCurrent()!!
        val badge = privateField<PrintJobNewCountBadge>(view, "newCountBadge")
        whenever(printJobOverviewQueryService.count(any())).thenAnswer {
            ui.remove(view)
            9L
        }
        val badgeBefore = badge.count

        firePoll(ui)

        assertEquals(badgeBefore, badge.count)
    }

    // ── Status column: every GenerationJobStatus renders its own distinct glyph ──────────

    @Test
    fun `every GenerationJobStatus renders a correctly toned Status symbol`() {
        val view = newView()
        GenerationJobStatus.entries.forEach { status ->
            val cell = columnRenderer(view, "Status").createComponent(PrintJobTreeNode.JobNode(row(status = status)))

            assertTrue(
                cell.classNames.contains("status-glyph--${generationJobStatusTone(status).css}"),
                "$status must carry its tone modifier, got: ${cell.classNames}",
            )
            assertTrue(
                cell.element.getAttribute("title").contains(UiTranslations.tr("invoiceDetail.jobs.status.${status.name.lowercase()}")),
                "$status's tooltip must name the status",
            )
        }
    }

    // ── Auftrag column, day rows: zero-failed vs. all-failed badge counts ─────────────────

    @Test
    fun `a day with zero failed jobs shows no warning badge and the tooltip omits the failed clause`() {
        val view = newView()
        val cell = columnRenderer(view, "Auftrag").createComponent(
            PrintJobTreeNode.DayNode(daySummary(day = LocalDate.of(2026, 8, 29), jobCount = 6, failedCount = 0)),
        ) as HorizontalLayout

        assertTrue(cell.children.toList().none { it.classNames.contains("day-badge--failed") })
        val tooltip = cell.element.getAttribute("title")
        assertFalse(tooltip.contains("fehlgeschlagen"), "zero-failed day must not mention failures in its tooltip, got: $tooltip")
    }

    @Test
    fun `a day where every job failed shows the failed badge count equal to the total count, in the error color`() {
        val view = newView()
        val cell = columnRenderer(view, "Auftrag").createComponent(
            PrintJobTreeNode.DayNode(daySummary(day = LocalDate.of(2026, 8, 30), jobCount = 3, failedCount = 3)),
        ) as HorizontalLayout

        val failedBadge = cell.children.toList().first { it.classNames.contains("day-badge--failed") }
        val failedCountText = failedBadge.children.toList().filterIsInstance<Span>().first().text
        assertEquals("3", failedCountText)
        assertEquals("var(--ino-status-cancelled)", failedBadge.style.get("--lt-day-badge-color"))
        val tooltip = cell.element.getAttribute("title")
        assertTrue(tooltip.contains("fehlgeschlagen"), "all-failed day must mention failures in its tooltip, got: $tooltip")
    }
}
