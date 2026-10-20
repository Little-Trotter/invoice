package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10.Routes
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._fireShortcut
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.Key
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.data.provider.hierarchy.AbstractBackEndHierarchicalDataProvider
import com.vaadin.flow.data.provider.hierarchy.HierarchicalQuery
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.HasErrorParameter
import com.vaadin.flow.router.RouterLink
import com.vaadin.flow.server.VaadinSession
import de.littletrotter.application.dto.GenerationJobFilter
import de.littletrotter.application.dto.PrintJobDaySummary
import de.littletrotter.application.dto.PrintJobOverviewRow
import de.littletrotter.application.invoice.port.inbound.PrintJobOverviewQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.fixture.aRecipient
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.GermanTime
import de.littletrotter.ui.UiEventBus
import de.littletrotter.ui.component.DataTreeTable
import de.littletrotter.ui.component.GridWrapper
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.OffsetDateTime
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * [PrintJobListView] against mocked [PrintJobOverviewQueryPort]/
 * [RecipientQueryPort]/[DocumentStore], same "reflection into
 * private fields/methods, no real routing" idiom as
 * [de.littletrotter.ui.view.message.MessageCenterViewKaribuTest].
 */
class PrintJobListViewKaribuTest {

    private val printJobOverviewQueryService: PrintJobOverviewQueryPort = mock()
    private val contactDataFacade: RecipientQueryPort = mock()
    private val documentStore: DocumentStore = mock()
    private val uiEventBus: UiEventBus = mock()

    @BeforeEach
    fun setup() {
        // Real route registration for InvoiceDetailView — the Rechnung column's RouterLink
        // throws unless the target route is actually registered (same reasoning as
        // MessageCenterViewKaribuTest's own setup).
        val testRoutes: MutableSet<Class<out Component>> = mutableSetOf(InvoiceDetailView::class.java, PrintJobListView::class.java)
        val testErrorRoutes: MutableSet<Class<out HasErrorParameter<*>>> = mutableSetOf()
        MockVaadin.setup(Routes(testRoutes, testErrorRoutes, true))
        whenever(printJobOverviewQueryService.count(any())).thenReturn(0L)
        whenever(printJobOverviewQueryService.findPaged(any(), any(), any())).thenReturn(emptyList())
        whenever(printJobOverviewQueryService.findDaySummaries(any())).thenReturn(emptyList())
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun newView(): PrintJobListView =
        PrintJobListView(printJobOverviewQueryService, contactDataFacade, documentStore, uiEventBus, time = FIXED_TIME_SOURCE)

    private inline fun <reified T : Any> privateField(view: PrintJobListView, name: String): T {
        val f = PrintJobListView::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(view).requireAs()
    }

    private fun grid(view: PrintJobListView): DataTreeTable<PrintJobTreeNode> = privateField(view, "grid")

    private fun treeDataProvider(view: PrintJobListView): AbstractBackEndHierarchicalDataProvider<PrintJobTreeNode, Void> =
        privateField(view, "treeDataProvider")

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
        attempts: Int = 0,
        lastError: String? = null,
        pdfObjectKey: String? = null,
        xmlObjectKey: String? = null,
        // date invariants (PO 2026-09-02) — overridable so a still-running job (finishedAt not
        // yet set) can be pinned too; createdAt itself has no override slot, deliberately: it is
        // PrintJobOverviewRow's own non-nullable field (mirroring GenerationJob.createdAt, the
        // print-date invariant), so no row this fixture builds can ever represent "no print date".
        finishedAt: OffsetDateTime? = OffsetDateTime.parse("2026-08-20T10:05:00Z"),
    ): PrintJobOverviewRow = PrintJobOverviewRow(
        jobId = jobId,
        invoiceId = invoiceId,
        invoiceNumber = invoiceNumber,
        recipientName = recipientName,
        status = status,
        createdAt = OffsetDateTime.parse("2026-08-20T10:00:00Z"),
        finishedAt = finishedAt,
        attempts = attempts,
        lastError = lastError,
        llmStatus = null,
        pdfObjectKey = pdfObjectKey,
        xmlObjectKey = xmlObjectKey,
    )

    private fun daySummary(day: LocalDate = LocalDate.of(2026, 8, 26), jobCount: Long = 12, failedCount: Long = 0): PrintJobDaySummary =
        PrintJobDaySummary(day, jobCount, failedCount)

    private fun auftragColumn(view: PrintJobListView): Grid.Column<PrintJobTreeNode> =
        grid(view).columns.first { it.headerText == "Auftrag" }

    private fun column(view: PrintJobListView, header: String): Grid.Column<PrintJobTreeNode> =
        grid(view).columns.first { it.headerText == header }

    // ── instantiation / columns / toolbar ───────────────────────────────────

    @Test
    fun `E1-9 the grid carries exactly the spec columns in order, without a Validierungsbefunde column`() {
        val view = newView()
        val headers = grid(view).columns.map { it.headerText }

        assertEquals(
            listOf("Auftrag", "Rechnung", "Empfängerprofil", "Status", "Zeit", "Download"),
            headers,
        )
        // Typ/Engine carry no operational information (PO, 2026-08-30) and the two full timestamp
        // columns collapsed into the single Zeit column — none of them may come back.
        listOf("Typ", "Engine", "Erstellt", "Beendet", "Validierungsbefunde").forEach { removed ->
            assertFalse(headers.contains(removed), "column '$removed' was removed and must stay removed, got: $headers")
        }
    }

    @Test
    fun `the Auftrag column renders the job id for a job row`() {
        val view = newView()
        val cell = auftragColumn(view).componentFor(PrintJobTreeNode.JobNode(row(jobId = 1L))) as Span

        assertEquals("#1", cell.text)
    }

    @Test
    fun `the Zeit column renders the time range and duration for a completed job`() {
        val view = newView()
        val cell = column(view, "Zeit").componentFor(PrintJobTreeNode.JobNode(row())) as HorizontalLayout

        val text = cell.children.toList().joinToString(" ") { (it as? Span)?.text ?: "" }
        assertTrue(text.contains("12:00 → 12:05"), "expected the time range in the Zeit cell, got: $text")
        assertTrue(text.contains("5 min"), "expected the duration in the Zeit cell, got: $text")
    }

    /**
     * date invariants (PO 2026-09-02) — the print-date invariant's UI border case: a job still in
     * flight has no `finishedAt` yet (renders "→ —", no duration), but `createdAt` — the print
     * date itself — always renders a real time regardless, never a placeholder. Unlike the
     * download/report columns' own "—" fallbacks (which cover a genuinely absent artifact),
     * `PrintJobOverviewRow.createdAt`/`GenerationJob.createdAt` are non-nullable end to end, so
     * there is no code path here left that could render a dash for the created half of this cell.
     */
    @Test
    fun `the Zeit column always renders a real created time, even for a still-running job with no finishedAt yet`() {
        val view = newView()
        val cell = column(view, "Zeit")
            .componentFor(PrintJobTreeNode.JobNode(row(status = GenerationJobStatus.RUNNING, finishedAt = null))) as HorizontalLayout

        val text = cell.children.toList().joinToString(" ") { (it as? Span)?.text ?: "" }
        // Same createdAt instant as the completed-job test above (2026-08-20T10:00:00Z), rendered
        // in GermanTime.ZONE — 12:00 CEST, not 10:00.
        assertTrue(text.contains("12:00"), "expected the real created time in the Zeit cell, got: $text")
        assertTrue(text.contains("→ —"), "expected the open-ended arrow for a still-running job, got: $text")
        assertFalse(text.contains("min") || text.contains(" h "), "no duration must render without a finishedAt, got: $text")
    }

    @Test
    fun `E1-1 the toolbar carries the filters, the hidden reset button and Search, in that order`() {
        val view = newView()
        val toolbar = view._find<HorizontalLayout>().first { it.classNames.contains("toolbar-wrap") }
        val customerFilter: ComboBox<RecipientSummaryDto> = privateField(view, "customerFilter")
        val dateFromFilter: DatePicker = privateField(view, "dateFromFilter")
        val dateToFilter: DatePicker = privateField(view, "dateToFilter")
        val searchButton: Button = privateField(view, "searchButton")
        val resetButton: Button = privateField(view, "resetFilterButton")

        assertEquals(listOf<Component>(customerFilter, dateFromFilter, dateToFilter, resetButton, searchButton), toolbar.children.toList())
        assertEquals("Empfängerprofil", customerFilter.element.getAttribute("aria-label"))
        assertEquals("Von", dateFromFilter.element.getAttribute("aria-label"))
        assertEquals("Bis", dateToFilter.element.getAttribute("aria-label"))
        assertEquals("Suchen", searchButton.text)
        assertEquals("Suchen", searchButton.element.getAttribute("aria-label"))
        assertTrue(searchButton.classNames.contains("print-jobs-search-button"))
        assertEquals("Filter zurücksetzen", resetButton.text)
        assertFalse(resetButton.isVisible)
    }

    // ── day (root) rows ──────────────────────────────────────────────────────

    /** A day row's own compact reading: helper to fetch the count text out of a
     *  `day-badge--*` `Span` (icon child + count `Span` child, see [PrintJobListView
     *  .buildDayCountBadge]). */
    private fun badgeCountText(cell: Component, cssClass: String): String =
        cell.children.toList().first { it.classNames.contains(cssClass) }
            .children.toList().filterIsInstance<Span>().first().text

    @Test
    fun `a day row with failures renders the date, the total badge and the failed badge, with the full sentence on hover`() {
        val view = newView()
        val cell = auftragColumn(view).componentFor(
            PrintJobTreeNode.DayNode(daySummary(day = LocalDate.of(2026, 8, 26), jobCount = 12, failedCount = 2)),
        ) as HorizontalLayout

        val dateSpan = cell.children.toList().first() as Span
        assertEquals("26.08.2026", dateSpan.text)
        assertEquals("12", badgeCountText(cell, "day-badge--total"))
        assertEquals("2", badgeCountText(cell, "day-badge--failed"))
        assertEquals("26.08.2026 — 12 Aufträge, 2 fehlgeschlagen", cell.element.getAttribute("title"))
    }

    @Test
    fun `a day row with no failures omits the failed badge and the tooltip's failed clause`() {
        val view = newView()
        val cell = auftragColumn(view).componentFor(
            PrintJobTreeNode.DayNode(daySummary(day = LocalDate.of(2026, 8, 25), jobCount = 5, failedCount = 0)),
        ) as HorizontalLayout

        assertEquals("5", badgeCountText(cell, "day-badge--total"))
        assertTrue(
            cell.children.toList().none { it.classNames.contains("day-badge--failed") },
            "no failed badge must render when failedCount is zero",
        )
        assertEquals("25.08.2026 — 5 Aufträge", cell.element.getAttribute("title"))
    }

    @Test
    fun `a day row where every job failed shows the failed badge count equal to the total`() {
        val view = newView()
        val cell = auftragColumn(view).componentFor(
            PrintJobTreeNode.DayNode(daySummary(day = LocalDate.of(2026, 8, 27), jobCount = 4, failedCount = 4)),
        ) as HorizontalLayout

        assertEquals("4", badgeCountText(cell, "day-badge--total"))
        assertEquals("4", badgeCountText(cell, "day-badge--failed"))
    }

    @Test
    fun `a day row with zero jobs and zero failed does not throw and shows a zero total badge`() {
        val view = newView()
        val cell = auftragColumn(view).componentFor(
            PrintJobTreeNode.DayNode(daySummary(day = LocalDate.of(2026, 8, 28), jobCount = 0, failedCount = 0)),
        ) as HorizontalLayout

        assertEquals("0", badgeCountText(cell, "day-badge--total"))
        assertTrue(cell.children.toList().none { it.classNames.contains("day-badge--failed") })
    }

    @Test
    fun `a day row renders as blank in every non-hierarchy column`() {
        val view = newView()
        val cell = column(view, "Rechnung").componentFor(PrintJobTreeNode.DayNode(daySummary()))

        assertTrue(cell is Div, "expected a blank Div for a day row, got: ${cell::class}")
    }

    @Test
    fun `the most recent day is expanded after beforeEnter`() {
        val newest = daySummary(day = LocalDate.of(2026, 8, 26))
        val older = daySummary(day = LocalDate.of(2026, 8, 20))
        whenever(printJobOverviewQueryService.count(any())).thenReturn(2L)
        whenever(printJobOverviewQueryService.findDaySummaries(any())).thenReturn(listOf(newest, older))

        val view = newView()
        view.beforeEnter(mock())

        assertTrue(grid(view).isExpanded(PrintJobTreeNode.DayNode(newest)))
        assertFalse(grid(view).isExpanded(PrintJobTreeNode.DayNode(older)))
    }

    @Test
    fun `child rows for a day are loaded with that day's own createdFrom-createdTo bounds`() {
        val day = LocalDate.of(2026, 8, 26)
        whenever(printJobOverviewQueryService.findPaged(any(), any(), any())).thenReturn(emptyList())
        val view = newView()

        treeDataProvider(view).fetchChildren(childQuery(PrintJobTreeNode.DayNode(daySummary(day = day))))

        val expectedStart = day.atStartOfDay(GermanTime.ZONE).toOffsetDateTime()
        val expectedEnd = day.plusDays(1).atStartOfDay(GermanTime.ZONE).toOffsetDateTime().minusNanos(1)
        verify(printJobOverviewQueryService).findPaged(
            eq(GenerationJobFilter(createdFrom = expectedStart, createdTo = expectedEnd)),
            eq(0),
            eq(50),
        )
    }

    @Test
    fun `root level children are the cached day nodes for the current filter, not a fresh query`() {
        whenever(printJobOverviewQueryService.count(any())).thenReturn(1L)
        whenever(printJobOverviewQueryService.findDaySummaries(any())).thenReturn(listOf(daySummary()))
        val view = newView()
        view.beforeEnter(mock())

        val children = treeDataProvider(view).fetchChildren(rootQuery()).toList()

        assertEquals(listOf(PrintJobTreeNode.DayNode(daySummary())), children)
        // findDaySummaries ran once, during beforeEnter's own refreshGrid — fetching the root
        // level again must not trigger a second call.
        verify(printJobOverviewQueryService, org.mockito.kotlin.times(1)).findDaySummaries(any())
    }

    // ── action columns ──────────────────────────────────────────────────────

    @Test
    fun `the Download column shows a PDF button for a completed job with an object key`() {
        val view = newView()
        val cell = column(view, "Download")
            .componentFor(PrintJobTreeNode.JobNode(row(jobId = 1L, status = GenerationJobStatus.COMPLETED, pdfObjectKey = "documents/re1.pdf")))

        val anchors = (cell as? com.vaadin.flow.component.orderedlayout.HorizontalLayout)?.children?.toList()
            ?.filterIsInstance<com.vaadin.flow.component.html.Anchor>()
        assertTrue(anchors?.isNotEmpty() == true, "expected at least one download Anchor, got: $cell")
        // Bytes are never fetched at render time, the anchor is a stable, jobId-scoped
        // route resolved server-side (DocumentDownloadResource), not a per-cell DownloadHandler.
        verify(documentStore, never()).get(any())
        assertEquals("documents/jobs/1/pdf", anchors!!.first().href)
    }

    /** The same column renderer re-invoked twice (simulating the exact
     *  `treeDataProvider.refreshAll()` a push/poll live update triggers, see [PrintJobListView
     *  .refreshGrid]) must produce byte-identical hrefs — a `DownloadHandler`-backed anchor could
     *  not make this guarantee, since every call minted a fresh `/VAADIN/dynamic/resource/<uiId>
     *  /<uuid>/` URL. */
    @Test
    fun `ST-A5 the download anchor's URL is stable across renders and is never a Vaadin dynamic resource`() {
        val view = newView()
        val downloadColumn = column(view, "Download")
        val jobRow = row(jobId = 5L, status = GenerationJobStatus.COMPLETED, pdfObjectKey = "documents/re5.pdf")

        val hrefFirst = anchorHref(downloadColumn.componentFor(PrintJobTreeNode.JobNode(jobRow)))
        val hrefAfterSimulatedRefresh = anchorHref(downloadColumn.componentFor(PrintJobTreeNode.JobNode(jobRow)))

        assertEquals("documents/jobs/5/pdf", hrefFirst)
        assertEquals(hrefFirst, hrefAfterSimulatedRefresh)
        assertFalse(hrefAfterSimulatedRefresh.startsWith("VAADIN/dynamic/resource"))
    }

    private fun anchorHref(cell: Component): String =
        (cell as com.vaadin.flow.component.orderedlayout.HorizontalLayout).children.toList()
            .filterIsInstance<com.vaadin.flow.component.html.Anchor>().first().href

    @Test
    fun `the Download column shows a dash when the job has no pdfObjectKey`() {
        val view = newView()
        val cell = column(view, "Download")
            .componentFor(PrintJobTreeNode.JobNode(row(status = GenerationJobStatus.COMPLETED, pdfObjectKey = null))) as Span

        assertEquals("—", cell.text)
        // No store round trip for a row that carries no key at all.
        verify(documentStore, never()).exists(any())
    }

    // ── Rechnung column: RouterLink to invoice/:id (job rows only) ─────────

    @Test
    fun `the Rechnung column renders the invoice number as a RouterLink for a job row`() {
        val view = newView()
        val cell = column(view, "Rechnung")
            .componentFor(PrintJobTreeNode.JobNode(row(invoiceId = 42L, invoiceNumber = "RE-2026-00042")))

        assertTrue(cell is RouterLink, "the Rechnung cell must be a RouterLink, not plain text")
        assertTrue((cell as RouterLink).text.contains("RE-2026-00042"))
    }

    // ── Status column: one compact glyph, attempts/last-error folded into its tooltip ───────

    @Test
    fun `a job with more than one attempt folds the attempts count into the status glyph's tooltip, not a separate badge`() {
        val view = newView()
        val cell = column(view, "Status")
            .componentFor(PrintJobTreeNode.JobNode(row(attempts = 3, lastError = "worker timeout")))

        val tooltip = cell.element.getAttribute("title")
        assertTrue(
            tooltip.contains(UiTranslations.tr("invoiceDetail.jobs.attempts", 3)),
            "expected the attempts clause in the status glyph's tooltip, got: $tooltip",
        )
    }

    @Test
    fun `a job at exactly one attempt omits the attempts clause from the status glyph's tooltip`() {
        val view = newView()
        val cell = column(view, "Status")
            .componentFor(PrintJobTreeNode.JobNode(row(attempts = 1)))

        val tooltip = cell.element.getAttribute("title")
        assertFalse(
            tooltip.contains(UiTranslations.tr("invoiceDetail.jobs.attempts", 1)),
            "1st attempt must not show an attempts clause, got: $tooltip",
        )
    }

    // A job the DLQ path terminally failed (GenerationJobService.markResultProcessingFailed) must
    // show up as failed here, the same real grid-column path every other job status already
    // renders through — proof the visible-failure-state requirement holds at the actual TreeGrid,
    // not only at the isolated buildJobStatusGlyph unit level (GenerationJobCellsTest).
    @Test
    fun `a DLQ-failed job shows the error glyph in the Status column, with the give-up reason in the tooltip`() {
        val view = newView()
        val giveUpReason = "processing failed after 3 attempts: db timeout"
        val cell = column(view, "Status")
            .componentFor(PrintJobTreeNode.JobNode(row(status = GenerationJobStatus.FAILED, lastError = giveUpReason)))

        assertTrue(
            cell.classNames.contains("status-glyph--error"),
            "expected the error-tone symbol, got: ${cell.classNames}",
        )
        val tooltip = cell.element.getAttribute("title")
        assertTrue(
            tooltip.contains(UiTranslations.tr("invoiceDetail.jobs.status.failed")),
            "the job must still read as failed in the tooltip, not stay on a pending/delegated/running label forever: $tooltip",
        )
        assertTrue(tooltip.contains(giveUpReason), "the DLQ give-up reason must still be reachable, not just the bare status: $tooltip")
    }

    // ── filters forward into the service calls as a GenerationJobFilter ────────

    // A real bus: attaching the view registers on it, which the mocked one would answer with null.
    private fun attachedView(): PrintJobListView {
        val view = PrintJobListView(printJobOverviewQueryService, contactDataFacade, documentStore, UiEventBus(), time = FIXED_TIME_SOURCE)
        UI.getCurrent().add(view)
        return view
    }

    @Test
    fun `E1-2 changing Empfaenger alone does not reload, clicking Search reloads with the recipient id`() {
        val recipient = aRecipient { id = 7L; name = "Acme" }
        val view = newView()
        val customerFilter: ComboBox<RecipientSummaryDto> = privateField(view, "customerFilter")
        val searchButton: Button = privateField(view, "searchButton")

        customerFilter.value = recipient.toRecipientSummaryDto()

        verify(printJobOverviewQueryService, never()).findDaySummaries(any())

        searchButton.click()

        // refreshGrid no longer issues a separate `count(filter)` round trip at all;
        // gridWrapper/emptyState visibility is derived from this same findDaySummaries result
        // (dayNodes), see refreshGrid's own KDoc.
        verify(printJobOverviewQueryService, org.mockito.kotlin.times(1)).findDaySummaries(eq(GenerationJobFilter(recipientId = 7L)))
        verify(printJobOverviewQueryService, never()).count(eq(GenerationJobFilter(recipientId = 7L)))
    }

    @Test
    fun `E1-3 changing von and bis alone does not reload, clicking Search reloads with a start-of-day to end-of-day window`() {
        val view = newView()
        val dateFromFilter: DatePicker = privateField(view, "dateFromFilter")
        val dateToFilter: DatePicker = privateField(view, "dateToFilter")
        val searchButton: Button = privateField(view, "searchButton")

        dateFromFilter.value = LocalDate.of(2026, 8, 1)
        dateToFilter.value = LocalDate.of(2026, 8, 25)

        verify(printJobOverviewQueryService, never()).findDaySummaries(any())

        searchButton.click()

        val expected = GenerationJobFilter(
            createdFrom = LocalDate.of(2026, 8, 1).atStartOfDay(GermanTime.ZONE).toOffsetDateTime(),
            createdTo = LocalDate.of(2026, 8, 25).atTime(23, 59, 59).atZone(GermanTime.ZONE).toOffsetDateTime(),
        )
        verify(printJobOverviewQueryService, org.mockito.kotlin.times(1)).findDaySummaries(eq(expected))
    }

    @Test
    fun `E1-4 pressing Enter in Empfaenger, von or bis triggers the same reload as the Search button`() {
        val view = attachedView()
        val customerFilter: ComboBox<RecipientSummaryDto> = privateField(view, "customerFilter")
        val dateFromFilter: DatePicker = privateField(view, "dateFromFilter")
        val dateToFilter: DatePicker = privateField(view, "dateToFilter")

        customerFilter._fireShortcut(Key.ENTER)
        verify(printJobOverviewQueryService, org.mockito.kotlin.times(1)).findDaySummaries(any())

        dateFromFilter._fireShortcut(Key.ENTER)
        verify(printJobOverviewQueryService, org.mockito.kotlin.times(2)).findDaySummaries(any())

        dateToFilter._fireShortcut(Key.ENTER)
        verify(printJobOverviewQueryService, org.mockito.kotlin.times(3)).findDaySummaries(any())
    }

    @Test
    fun `E1-5 with no filter values set Search shows all jobs unrestricted, newest day first`() {
        val newest = daySummary(day = LocalDate.of(2026, 8, 26))
        val older = daySummary(day = LocalDate.of(2026, 8, 25))
        whenever(printJobOverviewQueryService.findDaySummaries(any())).thenReturn(listOf(newest, older))
        val view = newView()
        val searchButton: Button = privateField(view, "searchButton")

        searchButton.click()

        verify(printJobOverviewQueryService).findDaySummaries(eq(GenerationJobFilter()))
        assertEquals(
            listOf(PrintJobTreeNode.DayNode(newest), PrintJobTreeNode.DayNode(older)),
            treeDataProvider(view).fetchChildren(rootQuery()).toList(),
        )
    }

    @Test
    fun `reset filter button clears staged filters and the persisted session state`() {
        val recipient = aRecipient { id = 3L; name = "Bob" }
        val view = newView()
        val customerFilter: ComboBox<RecipientSummaryDto> = privateField(view, "customerFilter")
        val dateFromFilter: DatePicker = privateField(view, "dateFromFilter")
        val resetButton: Button = privateField(view, "resetFilterButton")

        customerFilter.value = recipient.toRecipientSummaryDto()
        dateFromFilter.value = LocalDate.of(2026, 8, 1)
        assertTrue(resetButton.isVisible)

        resetButton.click()

        assertNull(customerFilter.value)
        assertNull(dateFromFilter.value)
        assertFalse(resetButton.isVisible)
        verify(printJobOverviewQueryService).findDaySummaries(eq(GenerationJobFilter()))
        assertEquals(PrintJobListFilterState(), VaadinSession.getCurrent()?.getAttribute(PrintJobListFilterState::class.java))
    }

    // ── empty state: one German sentence, no illustration ──────────────────────

    @Test
    fun `zero results show the German one-sentence empty state and hide the grid`() {
        // Visibility is derived from findDaySummaries alone, not a separate count() round trip;
        // stub that directly.
        whenever(printJobOverviewQueryService.findDaySummaries(any())).thenReturn(emptyList())
        val view = newView()
        // [PrintJobListView] deliberately never refreshes in its own init (see its class KDoc) —
        // a real navigation always runs beforeEnter, so tests that need a populated grid/empty
        // state trigger it explicitly too, same convention InvoiceListViewKaribuTest uses.
        view.beforeEnter(mock())
        val emptyState: Paragraph = privateField(view, "emptyState")
        val gridWrapper: GridWrapper<PrintJobTreeNode> = privateField(view, "gridWrapper")

        assertTrue(emptyState.isVisible)
        assertFalse(gridWrapper.isVisible)
        assertEquals("Es liegen keine E-Rechnungs-Aufträge vor, die zu den gewählten Filtern passen.", emptyState.text)
    }

    @Test
    fun `a non-zero result hides the empty state and shows the grid`() {
        // Same as above: driven by findDaySummaries, not count().
        whenever(printJobOverviewQueryService.findDaySummaries(any())).thenReturn(listOf(daySummary()))
        val view = newView()
        view.beforeEnter(mock())
        val emptyState: Paragraph = privateField(view, "emptyState")
        val gridWrapper: GridWrapper<PrintJobTreeNode> = privateField(view, "gridWrapper")

        assertFalse(emptyState.isVisible)
        assertTrue(gridWrapper.isVisible)
    }

    /**
     * Grid/empty-state visibility must never disagree with what
     * [PrintJobOverviewQueryPort.findDaySummaries] itself returned, no matter what
     * [PrintJobOverviewQueryPort.count] separately reports for the same filter. Previously,
     * `refreshGrid` derived visibility from `count(filter)` — a second, independent
     * round trip — while the grid itself rendered `findDaySummaries(filter)`; a stub (or, in
     * production, a row committed between the two real queries) where the two disagree used to
     * show either an empty grid over real data or the empty state over a populated one. Every
     * combination of `count`/`findDaySummaries` results is exercised here — visibility now only
     * ever tracks the latter, so the two are never simultaneously visible and never simultaneously
     * hidden.
     */
    @ParameterizedTest(name = "count={0}, daySummaryCount={1}")
    @CsvSource("0,0", "0,3", "5,0", "5,3")
    fun `grid and empty state are never both visible or both hidden, regardless of what count() disagrees with`(
        countResult: Long,
        daySummaryCount: Int,
    ) {
        whenever(printJobOverviewQueryService.count(any())).thenReturn(countResult)
        whenever(printJobOverviewQueryService.findDaySummaries(any()))
            .thenReturn((1..daySummaryCount).map { daySummary(day = LocalDate.of(2026, 8, it + 20)) })
        val view = newView()
        view.beforeEnter(mock())
        val emptyState: Paragraph = privateField(view, "emptyState")
        val gridWrapper: GridWrapper<PrintJobTreeNode> = privateField(view, "gridWrapper")

        assertTrue(
            emptyState.isVisible != gridWrapper.isVisible,
            "exactly one of grid/empty-state must be visible — emptyState=${emptyState.isVisible}, gridWrapper=${gridWrapper.isVisible}",
        )
        assertEquals(daySummaryCount == 0, emptyState.isVisible, "visibility must track findDaySummaries, not count()")
        assertEquals(daySummaryCount > 0, gridWrapper.isVisible, "visibility must track findDaySummaries, not count()")
    }

    // ── session persistence round-trip ──────────────────────────────────────────

    @Test
    fun `E1-7 filter state searched in one view instance restores into a fresh one via beforeEnter`() {
        val recipient = aRecipient { id = 3L; name = "Bob" }
        whenever(contactDataFacade.findRecipientById(3L)).thenReturn(recipient.toRecipientSummaryDto())

        val view1 = newView()
        val customerFilter1: ComboBox<RecipientSummaryDto> = privateField(view1, "customerFilter")
        val dateFromFilter1: DatePicker = privateField(view1, "dateFromFilter")
        val dateToFilter1: DatePicker = privateField(view1, "dateToFilter")
        val searchButton1: Button = privateField(view1, "searchButton")
        customerFilter1.value = recipient.toRecipientSummaryDto()
        dateFromFilter1.value = LocalDate.of(2026, 8, 1)
        dateToFilter1.value = LocalDate.of(2026, 8, 25)

        // Field changes alone stage nothing in the session; only a search persists them.
        assertNull(VaadinSession.getCurrent()?.getAttribute(PrintJobListFilterState::class.java))

        searchButton1.click()

        val persisted = VaadinSession.getCurrent()?.getAttribute(PrintJobListFilterState::class.java)
        assertEquals(PrintJobListFilterState(3L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 25)), persisted)

        // A fresh instance (e.g. a later navigation via MainLayout's menu, which carries no query
        // string of its own) restores from the session on beforeEnter.
        val view2 = newView()
        val event: BeforeEnterEvent = mock()
        view2.beforeEnter(event)

        val customerFilter2: ComboBox<RecipientSummaryDto> = privateField(view2, "customerFilter")
        val dateFromFilter2: DatePicker = privateField(view2, "dateFromFilter")
        val dateToFilter2: DatePicker = privateField(view2, "dateToFilter")
        assertEquals(3L, customerFilter2.value?.id)
        assertEquals(LocalDate.of(2026, 8, 1), dateFromFilter2.value)
        assertEquals(LocalDate.of(2026, 8, 25), dateToFilter2.value)
    }

    // ── customerFilter is a lazy combo (RecipientQueryPort.findRecipients/countRecipients), not
    // findAllRecipients()'s capped-at-60 eager list. These drive the combo's own DataProvider
    // with a typed filter — exactly how the ComboBox itself queries once wired via lazyItems —
    // as the regression test proving the old cap's bug is gone. ──────────────────────────────

    private fun customerFilterDataProvider(view: PrintJobListView): com.vaadin.flow.data.provider.DataProvider<RecipientSummaryDto, String> {
        val customerFilter: ComboBox<RecipientSummaryDto> = privateField(view, "customerFilter")
        return customerFilter.dataProvider.requireAs()
    }

    private fun customerQuery(offset: Int = 0, limit: Int = 60, filter: String? = null) =
        com.vaadin.flow.data.provider.Query<RecipientSummaryDto, String>(offset, limit, emptyList(), null, filter)

    @Test
    fun `customerFilter's lazy data provider finds a recipient beyond the old 60-row cap via a typed filter`() {
        // Alphabetically the 61st customer — findAllRecipients()'s old page-0-of-60 fetch could
        // never have returned this, no matter what the user typed; exactly the bug this lazy
        // combo fixes.
        val beyondCap = aRecipient { id = 161L; name = "Zeta Logistik #61" }
        whenever(contactDataFacade.findRecipients(0, 60, "Zeta")).thenReturn(listOf(beyondCap.toRecipientSummaryDto()))
        whenever(contactDataFacade.countRecipients("Zeta")).thenReturn(1L)
        val view = newView()

        val result = customerFilterDataProvider(view).fetch(customerQuery(filter = "Zeta")).toList()
        val size = customerFilterDataProvider(view).size(customerQuery(filter = "Zeta"))

        assertEquals(listOf(beyondCap.toRecipientSummaryDto()), result)
        assertEquals(1, size)
        verify(contactDataFacade).findRecipients(0, 60, "Zeta")
        verify(contactDataFacade).countRecipients("Zeta")
    }

    @Test
    fun `customerFilter's lazy data provider returns nothing for a filter with no match, without falling back to the eager list`() {
        whenever(contactDataFacade.findRecipients(0, 60, "Nichts-passt")).thenReturn(emptyList())
        whenever(contactDataFacade.countRecipients("Nichts-passt")).thenReturn(0L)
        val view = newView()

        val result = customerFilterDataProvider(view).fetch(customerQuery(filter = "Nichts-passt")).toList()
        val size = customerFilterDataProvider(view).size(customerQuery(filter = "Nichts-passt"))

        assertTrue(result.isEmpty())
        assertEquals(0, size)
    }

    @Test
    fun `customerFilter's lazy data provider normalises a blank typed filter to the unfiltered fetch`() {
        val recipient = aRecipient { id = 5L; name = "Acme" }
        whenever(contactDataFacade.findRecipients(0, 60, null)).thenReturn(listOf(recipient.toRecipientSummaryDto()))
        val view = newView()

        val result = customerFilterDataProvider(view).fetch(customerQuery(filter = "   ")).toList()

        assertEquals(listOf(recipient.toRecipientSummaryDto()), result)
        verify(contactDataFacade).findRecipients(0, 60, null)
    }
}
