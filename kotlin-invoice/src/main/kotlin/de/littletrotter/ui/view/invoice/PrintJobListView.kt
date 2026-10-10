package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.AttachEvent
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.DetachEvent
import com.vaadin.flow.component.Key
import com.vaadin.flow.component.Shortcuts
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.data.provider.hierarchy.AbstractBackEndHierarchicalDataProvider
import com.vaadin.flow.data.provider.hierarchy.HierarchicalQuery
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.RouteParameters
import com.vaadin.flow.router.RouterLink
import com.vaadin.flow.router.Route
import com.vaadin.flow.server.Command
import com.vaadin.flow.server.VaadinSession
import de.littletrotter.application.dto.GenerationJobFilter
import de.littletrotter.application.dto.PrintJobDaySummary
import de.littletrotter.application.dto.PrintJobOverviewRow
import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.application.invoice.PrintJobOverviewQueryService
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.GermanTime
import de.littletrotter.ui.MainLayout
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.DataTreeTable
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asActionColumn
import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.asTextColumn
import de.littletrotter.ui.component.GridWrapper
import de.littletrotter.ui.component.PageShell
import de.littletrotter.ui.component.ellipsizedTextColumn
import de.littletrotter.ui.component.recipientItems
import de.littletrotter.ui.format.DateFormatter
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.UiEventBus
import jakarta.inject.Inject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.stream.Stream

private const val ARIA_LABEL = "aria-label"
private const val TITLE_ATTR = "title"
private const val PRINT_JOBS_TITLE_KEY = "printJobs.title"
private const val PRINT_JOBS_FILTER_SEARCH_KEY = "printJobs.filter.search"
private const val PRINT_JOBS_FILTER_RESET_KEY = "printJobs.filter.reset"
private const val END_OF_DAY_HOUR = 23
private const val END_OF_DAY_MINUTE_SECOND = 59

/**
 * Session-held filter state for [PrintJobListView], the same "one `Serializable` data
 * class round-tripped through [VaadinSession]" mechanism [InvoiceListView]'s own
 * `InvoiceListFilterState` uses. Deliberately narrower: no URL query-parameter
 * round-trip (this view has no deep-link requirement) and no paging (a lazy
 * hierarchical data provider scrolls/re-fetches on its own, there is no page index to remember).
 */
internal data class PrintJobListFilterState(
    val recipientId: Long? = null,
    val dateFrom: LocalDate? = null,
    val dateTo: LocalDate? = null,
) : java.io.Serializable

/**
 * One row of [PrintJobListView]'s two-level TreeGrid: either a day (root, [DayNode]) or one
 * of that day's jobs (child, [JobNode]). A sealed type rather than two separate grids/columns sets
 * because a single [com.vaadin.flow.component.treegrid.TreeGrid] needs one item type for its
 * hierarchical data provider; every column's cell builder pattern-matches on this to decide what
 * (if anything) it renders for a given row — see [PrintJobListView.jobOnly].
 */
internal sealed interface PrintJobTreeNode {
    /**
     * fix-print-job-list-blank-rows — identity is the DAY, not the whole summary. TreeGrid keys
     * its expansion state (and the client-side hierarchical cache) by item equality; with data
     * class equality over [summary], every live-update refresh minted a new, "different" DayNode
     * the moment the day's job count changed, so [PrintJobListView.refreshGrid]'s expand call
     * collapsed and re-expanded the day on EVERY push. Overlapping push refreshes (a job's
     * DELEGATED→COMPLETED transitions arrive within a second) then interleaved with in-flight
     * client fetches and corrupted the grid's virtualizer — physical rows without any cell
     * content until a full page reload. Day-keyed equality keeps the expanded day recognizable
     * across refreshes; the freshly fetched instance still carries the updated [summary] for
     * rendering.
     */
    class DayNode(val summary: PrintJobDaySummary) : PrintJobTreeNode {
        override fun equals(other: Any?): Boolean = other is DayNode && other.summary.day == summary.day
        override fun hashCode(): Int = summary.day.hashCode()
        override fun toString(): String = "DayNode(day=${summary.day})"
    }

    data class JobNode(val row: PrintJobOverviewRow) : PrintJobTreeNode
}

/** The non-terminal [GenerationJobStatus] values, mirroring the inline
 *  check [InvoiceDetailView.hasActiveGenerationJobs] uses for the same purpose. Used by
 *  [PrintJobListView.hasActiveJobsInResultSet] to decide whether the poll fallback still has
 *  anything to watch for. */
internal val ACTIVE_GENERATION_JOB_STATUSES: Set<GenerationJobStatus> =
    setOf(GenerationJobStatus.PENDING, GenerationJobStatus.DELEGATED, GenerationJobStatus.RUNNING)

/** How far back a job's creation may lie to still count as "new" in the badge. */
internal const val NEW_JOBS_WINDOW_MINUTES = 30L

/**
 * The global "E-Rechnungs-Aufträge" view: every [de.littletrotter.domain.invoice
 * .GenerationJob] across every invoice, grouped by day (newest first), in one place — the
 * operational answer to "what did the system create, and did anything fail?" without clicking
 * through invoices one by one.
 *
 * Read-only v1 (PO decision): no republish/cancel actions
 * here.
 *
 * **Two-level TreeGrid:** root rows are calendar days (via [PrintJobOverviewQueryService
 * .findDaySummaries], honoring the same [GenerationJobFilter] as the job rows), lazily expanding
 * into that day's jobs via [PrintJobOverviewQueryService.findPaged] narrowed to that day's own
 * bounds ([dayFilter]) — both levels are backed by [treeDataProvider], never loading more than one
 * page of either level at a time.
 *
 * **Action column:** the Download column is built from the *lightweight* fields added
 * to [PrintJobOverviewRow] (`pdfObjectKey`/`xmlObjectKey`) — no per-row entity load, and no
 * [DocumentStore] round trip until an operator actually clicks a download. Rendered with the exact
 * same cell builder ([buildDownloadAction]) [InvoiceExportPanel]'s own per-invoice grid uses, via
 * the row-shaped overload added alongside its entity-shaped original.
 *
 * **Toolbar:** the filter is exactly Empfänger ([GenerationJobFilter.recipientId]), von and
 * bis ([GenerationJobFilter.createdFrom]/[GenerationJobFilter.createdTo], whole calendar days in
 * [GermanTime.ZONE]) plus an explicit Search button — no status, year or invoice-number filter.
 * Field changes only stage values; [refreshGrid] runs on the Search click or Enter in one of the
 * three fields, and once on entry with the values restored from [PrintJobListFilterState].
 *
 * **New-jobs badge:** live updates (push and poll) never touch the grid; they only recompute
 * [newCountBadge] via [refreshNewCountBadge], which counts jobs of the entered filter created
 * after [lastGridLoadAt] and within the last [NEW_JOBS_WINDOW_MINUTES] minutes ([newJobsFilter]).
 * The grid changes only on entry and on Search, and Search resets the badge.
 *
 * The Status column's attempts badge/last-error tooltip is built the same way this view always
 * did: [PrintJobOverviewRow] already carries `attempts`/`lastError`/`llmStatus` as plain fields, so
 * [buildRowStatusCell] recomposes the same look as [buildJobStatusCell] from those flat fields
 * plus the reusable [buildJobStatusGlyph], without touching the entity.
 */
@Route(value = "e-invoice-jobs", layout = MainLayout::class)
@PageTitle("E-Rechnungs-Aufträge")
class PrintJobListView
@Inject
constructor(
    private val printJobOverviewQueryService: PrintJobOverviewQueryService,
    private val recipientQuery: RecipientQueryPort,
    private val documentStore: DocumentStore,
    // Same mechanism [InvoiceDetailView] uses: the view registers on [UiEventBus] and polls so the
    // new-jobs badge follows finished jobs. See [onAttach]/[onDetach] and [hasActiveJobsInResultSet].
    private val uiEventBus: UiEventBus,
    private val time: TimeSource,
) : VerticalLayout(), BeforeEnterObserver, TranslationProvider {

    private val customerFilter = ComboBox<RecipientSummaryDto>()
    private val dateFromFilter = DatePicker()
    private val dateToFilter = DatePicker()
    private lateinit var searchButton: Button
    private lateinit var resetFilterButton: Button
    private val newCountBadge = PrintJobNewCountBadge()
    private var isInitializing = false

    // When the grid was last loaded. The badge only counts jobs created after it, so a job the
    // grid already shows is never announced as "new" again once Search has reset the badge.
    private var lastGridLoadAt: OffsetDateTime = time.nowUtc()

    // The root (day) rows of the current filter, refreshed by [refreshGrid]. Held as a
    // field (not re-fetched inside the data provider's own fetch/count callbacks) so the exact
    // same [PrintJobTreeNode.DayNode] instances are used both for rendering and for the
    // "most recent day expanded by default" [grid.expand] call right after a refresh.
    private var dayNodes: List<PrintJobTreeNode.DayNode> = emptyList()

    // fix-print-job-list-blank-rows — the day [refreshGrid] last auto-expanded. Guards the
    // "most recent day expanded by default" behavior so it fires once per day-change instead of
    // on every live-update refresh; see refreshGrid's own KDoc.
    private var lastAutoExpandedDay: LocalDate? = null

    private val treeDataProvider = PrintJobTreeDataProvider()
    private val grid = DataTreeTable(PrintJobTreeNode::class.java)
    private val gridWrapper = GridWrapper(grid)
    private val emptyState = Paragraph().apply { addClassName("empty-state"); isVisible = false }

    // Live-update plumbing (same mechanism [InvoiceDetailView] uses): [UiEventBus] registration
    // for the fast path (worker and web run in the same JVM) plus a poll-interval fallback for
    // the guaranteed path (worker runs in a separate process, so the in-memory bus never fires
    // here). Shared with [InvoiceDetailView]/[InvoiceListView] via [PollingViewLifecycle].
    // See [onAttach]/[onDetach]/[hasActiveJobsInResultSet].
    private val pollingLifecycle = PollingViewLifecycle()

    // Whether the previous poll tick still saw a non-terminal job (PENDING/DELEGATED/RUNNING) in
    // the current filter's result set. Needed for one final refresh on the tick where the last
    // such job leaves that set — see [shouldRefreshOnPollTick]'s own KDoc (defined alongside
    // [InvoiceDetailView], this view's sibling fix for the identical gap) for why that tick must
    // not be skipped.
    @Volatile
    private var sawActiveJobsInResultSet = false

    init {
        setSizeFull()
        isPadding = true
        isSpacing = true
        configureGrid()
        val shell = PageShell(tr(PRINT_JOBS_TITLE_KEY)).apply {
            filters(buildToolbar())
            content(newCountBadge, gridWrapper, emptyState)
        }
        shell.setSizeFull()
        add(shell)
        expand(shell)
        grid.setDataProvider(treeDataProvider)
        updatePageTitle()
        // Deliberately no init-time [refreshGrid] call — see [beforeEnter]'s own KDoc, same
        // "never refresh in init, only in the router callback" discipline [InvoiceListView]
        // follows (identical reasoning: this instance's still-default filter state must not
        // clobber whatever an earlier instance already persisted to the session before
        // [beforeEnter] gets a chance to read it back).
    }

    override fun getComponent(): Component = this

    private fun trArgs(key: String, args: List<Any>): String = tr(key, *args.toTypedArray())

    private fun updatePageTitle() {
        UI.getCurrent().page.setTitle(tr(PRINT_JOBS_TITLE_KEY))
    }

    // ── live update ───────────────────────────────────────────────────────

    /**
     * Fast path: a [de.littletrotter.ui.UiEventBus.JobFinishedEvent] fires whenever any job
     * finishes anywhere in the system, so — unlike [InvoiceDetailView]'s per-invoice filter on
     * `event.invoiceId` — every event recomputes the badge of this global view.
     *
     * Push and poll drive [refreshNewCountBadge] only, never [refreshGrid]: the grid reloads
     * on entry and on an explicit Search, so it no longer repaints under the operator's hands.
     * The handler stays unfiltered (no cheap in-memory membership test against a bare
     * `JobFinishedEvent`); that is affordable now because each event costs one `count` query, not
     * a day/tree reload. Every trigger recomputes the count from the database instead of
     * incrementing it, so overlapping push, poll and visibility updates converge on the same
     * number.
     *
     * Guaranteed path: the poll listener below. Generation jobs can complete in a separate worker
     * process (see [de.littletrotter.infrastructure.messaging.GenerationJobResultConsumer]),
     * whose own in-memory [UiEventBus] instance never
     * reaches this JVM's listeners; polling is the only channel that is guaranteed to observe the
     * result. The effective poll interval is capped at 2000 ms so the PO's 2-second visibility
     * requirement holds even on the polling path, and ticks are skipped whenever the entered
     * filter's result set holds no non-terminal job (PENDING/DELEGATED/RUNNING) — see
     * [hasActiveJobsInResultSet]/[shouldRefreshOnPollTick] (the latter defined alongside
     * [InvoiceDetailView], this view's sibling fix for the identical gap) — except for the one
     * guaranteed tick right after attaching, see [sawActiveJobsInResultSet]'s seeding below.
     */
    override fun onAttach(attachEvent: AttachEvent) {
        super.onAttach(attachEvent)
        val ui = attachEvent.ui
        // Seeded true so the very next poll
        // tick after entering this view always refreshes once, even if the result set already
        // looks fully terminal by then. Without this, a job created elsewhere (e.g. from
        // InvoiceDetailView) whose entire PENDING→...→COMPLETED lifetime falls between
        // navigating here and the first tick is never caught: both `hadActiveJobs` and
        // `hasActiveJobs` read false and shouldRefreshOnPollTick skips the tick — the same
        // "whole lifetime falls between two ticks" hole InvoiceDetailView closes by seeding its
        // own flag from its onPrintScheduled callback. This view has no comparable creation
        // event to hook (it never creates jobs itself), so it seeds unconditionally on attach.
        sawActiveJobsInResultSet = true
        pollingLifecycle.attach(
            ui,
            LIVE_UPDATE_POLL_INTERVAL_MS,
            // A push already queued via UI.access before this view detached (see
            // UiEventBus.publish) is not cancelled by unregistering here; the target UI can
            // stay attached (same browser tab, user just navigated elsewhere) while this
            // specific view/component no longer is. Guard on both before touching the badge.
            register = { u ->
                uiEventBus.registerOffLock(
                    u,
                    eventTick(
                        u,
                        capture = ::capturePollSnapshot,
                        load = { snapshot, _ -> newJobsCount(snapshot.filter, snapshot.lastGridLoadAt) },
                        apply = { count, _ -> if (isAttached && u.isAttached) newCountBadge.update(count) },
                    ),
                )
            },
            tick = pollTick(capture = ::capturePollSnapshot, load = ::loadPollTick, apply = ::applyPollTick),
        )
    }

    override fun onDetach(detachEvent: DetachEvent) {
        super.onDetach(detachEvent)
        pollingLifecycle.detach(detachEvent.ui)
    }

    /** Whether [currentFilter]'s own result set contains at least one non-terminal job
     *  ([ACTIVE_GENERATION_JOB_STATUSES]) — the only kind this view could still see transition on
     *  its own. */
    private fun hasActiveJobsInResultSet(filter: GenerationJobFilter): Boolean =
        printJobOverviewQueryService.count(filter.copy(statuses = ACTIVE_GENERATION_JOB_STATUSES)) > 0

    /** What the poll load needs from the components, read under the session lock. */
    private data class PollSnapshot(val filter: GenerationJobFilter, val lastGridLoadAt: OffsetDateTime)

    private fun capturePollSnapshot() = PollSnapshot(currentFilter(), lastGridLoadAt)

    /** Poll load phase, off the session lock: the active-job query and, on a state change, the
     *  badge count. Null means nothing to render. */
    private fun loadPollTick(snapshot: PollSnapshot): Long? {
        val hadActiveJobs = sawActiveJobsInResultSet
        val hasActiveJobs = hasActiveJobsInResultSet(snapshot.filter)
        sawActiveJobsInResultSet = hasActiveJobs
        if (!shouldRefreshOnPollTick(hadActiveJobs, hasActiveJobs)) return null
        return newJobsCount(snapshot.filter, snapshot.lastGridLoadAt)
    }

    private fun applyPollTick(count: Long?) {
        if (count == null || !isAttached) return
        newCountBadge.update(count)
    }

    /** "N new" count for [entered]; 0 without any entered field, where the query is skipped. */
    private fun newJobsCount(entered: GenerationJobFilter, gridLoadedAt: OffsetDateTime): Long {
        if (entered.recipientId == null && entered.createdFrom == null && entered.createdTo == null) return 0L
        return printJobOverviewQueryService.count(newJobsFilter(entered, time.nowUtc(), gridLoadedAt))
    }

    /** Recomputes the "N new" badge from one `count` query for the currently entered fields;
     *  touches nothing but [newCountBadge]. Used by explicit user actions, not by push/poll. */
    private fun refreshNewCountBadge() {
        newCountBadge.update(newJobsCount(currentFilter(), lastGridLoadAt))
    }

    /** [entered] narrowed to jobs created in the last [NEW_JOBS_WINDOW_MINUTES] minutes and
     *  after the grid was last loaded (whichever is later); the range's own upper bound is kept. */
    private fun newJobsFilter(
        entered: GenerationJobFilter,
        now: OffsetDateTime,
        gridLoadedAt: OffsetDateTime,
    ): GenerationJobFilter {
        val windowStart = maxOf(now.minusMinutes(NEW_JOBS_WINDOW_MINUTES), gridLoadedAt)
        val from = entered.createdFrom?.let { maxOf(it, windowStart) } ?: windowStart
        return entered.copy(createdFrom = from)
    }

    // ── grid ─────────────────────────────────────────────────────────────────

    /**
     * Wraps a job-row cell builder so it renders nothing for a [PrintJobTreeNode.DayNode]
     * (an empty [Div], not a dash — a day row shows its summary once, in the hierarchy column
     * only; every other column stays blank rather than repeating a placeholder across the row).
     */
    private fun jobOnly(builder: (PrintJobOverviewRow) -> Component): (PrintJobTreeNode) -> Component = { node ->
        when (node) {
            is PrintJobTreeNode.JobNode -> builder(node.row)
            is PrintJobTreeNode.DayNode -> Div()
        }
    }

    private fun configureGrid() {
        grid.setSizeFull()
        grid.addClassName("print-jobs-table")

        // The hierarchy column doubles as the "Auftrag" column: a day's own compact date+badge
        // rendering (see [buildDayRowCell]) for a [PrintJobTreeNode.DayNode], the job id
        // for a [PrintJobTreeNode.JobNode] — the same idiom every day-grouped TreeGrid uses (one
        // column carries both the toggle arrow and whatever label the current row actually has). A
        // compact: the free space belongs to the Kunde column; the day summary sentence lives in
        // this cell's `title` tooltip, so the cell content stays short.
        grid.addComponentHierarchyColumn { node ->
            when (node) {
                is PrintJobTreeNode.DayNode -> buildDayRowCell(node.summary)
                is PrintJobTreeNode.JobNode -> Span("#${node.row.jobId}").apply { addClassName("lt-job-id") }
            }
        }.setHeader(tr("printJobs.column.job")).asCompactColumn()

        grid.addComponentColumn(jobOnly { row ->
            RouterLink(row.invoiceNumber, InvoiceDetailView::class.java, RouteParameters("id", row.invoiceId.toString()))
        }).setHeader(tr("printJobs.column.invoice")).asCompactColumn()

        grid.ellipsizedTextColumn { node ->
            when (node) {
                is PrintJobTreeNode.JobNode -> node.row.recipientName ?: "—"
                is PrintJobTreeNode.DayNode -> ""
            }
        }.setHeader(tr("printJobs.column.customer"))
            .asTextColumn(TextColumnWidth.MEDIUM)

        grid.addComponentColumn(jobOnly { row -> buildRowStatusCell(row) })
            .setHeader(tr("printJobs.column.status"))
            .asCompactColumn()

        // One "Zeit" column instead of separate Erstellt/Beendet ones: the day group row already
        // names the date, so `12:00 → 12:05` plus a duration says everything the two full
        // timestamp columns used to — in a third of the width.
        grid.addComponentColumn(jobOnly { row -> buildTimeRangeCell(row.createdAt, row.finishedAt) })
            .setHeader(tr("printJobs.column.time"))
            .asCompactColumn()

        // Download: PDF/XML anchor pair via DownloadHandler, bytes fetched from DocumentStore
        // only once a link is actually clicked.
        grid.addComponentColumn(jobOnly { row -> buildDownloadAction(row, documentStore) { key -> tr(key) } })
            // Ans Ende geheftet, aus demselben Grund wie in InvoiceExportPanel (PO-Meldung
            // 05.09.2026): diese Übersicht trägt noch mehr Spalten als die im Rechnungsdetail,
            // und die Download-Handlung darf nicht diejenige sein, die hinter dem rechten Rand
            // verschwindet.
            .asActionColumn().setHeader(tr("printJobs.column.download"))

        // Same "row click navigates to the invoice" idiom this view always had — job rows only; a day
        // row's own click target is its hierarchy column's expand/collapse arrow, not this
        // listener (TreeGrid handles that internally).
        grid.addItemClickListener { event -> (event.item as? PrintJobTreeNode.JobNode)?.let { navigateToInvoice(it.row.invoiceId) } }
        grid.element.setAttribute(ARIA_LABEL, tr(PRINT_JOBS_TITLE_KEY))
    }

    /** "26.08.2026 — 12 Aufträge, 2 fehlgeschlagen" (the failed-count clause only when
     *  greater than zero, per spec). No longer rendered on-screen directly; it is
     *  now [buildDayRowCell]'s `title` tooltip, the on-screen cell itself is the compact date +
     *  badge rendering below. */
    private fun dayNodeLabel(summary: PrintJobDaySummary): String {
        val dateLabel = DateFormatter.format(summary.day)
        return if (summary.failedCount > 0) {
            tr("printJobs.day.summaryWithFailed", dateLabel, summary.jobCount, summary.failedCount)
        } else {
            tr("printJobs.day.summary", dateLabel, summary.jobCount)
        }
    }

    /**
     * The Auftrag column's day-row rendering: date + a compact total-count badge, plus (only
     * when [PrintJobDaySummary.failedCount] is greater than zero) a second, error-colored badge —
     * no prose. [dayNodeLabel]'s full sentence moves onto this cell's own `title` attribute as a
     * mouseover, this repo's established tooltip convention (`element.setAttribute(TITLE_ATTR, ...)`,
     * not the unused Vaadin `Tooltip` component). Replaces the former single [Span] whose
     * `setAutoWidth` sizing this label used to dominate — see the class's `configureGrid` KDoc on
     * the hierarchy column.
     */
    private fun buildDayRowCell(summary: PrintJobDaySummary): Component {
        val dateLabel = Span(DateFormatter.format(summary.day)).apply { addClassName("lt-day-date") }
        val totalBadge = buildDayCountBadge(VaadinIcon.FILE_TEXT_O, summary.jobCount, "--lumo-secondary-text-color", "day-badge--total")
        return HorizontalLayout(dateLabel, totalBadge).apply {
            isPadding = false; isSpacing = false
            addClassName("lt-day-row")
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
            if (summary.failedCount > 0) {
                add(buildDayCountBadge(VaadinIcon.WARNING, summary.failedCount, "--ino-status-cancelled", "day-badge--failed"))
            }
            element.setAttribute(TITLE_ATTR, dayNodeLabel(summary))
        }
    }

    /** One icon + count, e.g. "🗎 12" / "⚠ 2". [colorVar] is a bare `--ino-status-*`/Lumo
     *  custom-property name (not pre-wrapped in `var(...)`) so callers stay declarative; the failed
     *  badge's `--ino-status-cancelled` is the same general rejection-red styles.css documents as
     *  the shared error face beyond the CANCELLED invoice status itself (see
     *  [generationJobStatusColorVar]'s own KDoc). */
    private fun buildDayCountBadge(icon: VaadinIcon, count: Long, colorVar: String, cssClass: String): Component =
        Span().apply {
            addClassNames("lt-day-badge", cssClass)
            style.set("--lt-day-badge-color", "var($colorVar)")
            add(icon.create().apply { setSize("0.85rem") })
            add(Span(count.toString()))
        }

    /**
     * The Status column's cell, recomposed from [PrintJobOverviewRow]'s own flat
     * `attempts`/`lastError` fields rather than [GenerationJobCells
     * .buildJobStatusCell] (which needs the full entity) — see this class's own KDoc for why. One
     * status symbol ([buildJobStatusGlyph]); attempts count (only past the first attempt) and last
     * error (when present) fold into its tooltip ([buildStatusTooltip]).
     */
    private fun buildRowStatusCell(row: PrintJobOverviewRow): Component =
        buildJobStatusGlyph(row.status, buildStatusTooltip(row.status, row.attempts, row.lastError, ::trArgs))

    private fun navigateToInvoice(invoiceId: Long) {
        UI.getCurrent().navigate(InvoiceDetailView::class.java, RouteParameters("id", invoiceId.toString()))
    }

    // ── toolbar / filters ───────────────────────────────────────────────────

    private fun buildToolbar(): Component {
        customerFilter.placeholder = tr("printJobs.filter.customer")
        customerFilter.element.setAttribute(ARIA_LABEL, tr("printJobs.filter.customer"))
        // Was findAllRecipients(), capped at page-size.default (60): silently dropped
        // every customer beyond the first page alphabetically. The offset→pageIndex/
        // RecipientQueryPort wiring itself now lives in the shared [recipientItems] helper (was
        // duplicated verbatim with InvoiceListView's recipientFilter).
        customerFilter.recipientItems(recipientQuery)
        customerFilter.setItemLabelGenerator { it.name }
        customerFilter.isClearButtonVisible = true
        customerFilter.width = "14rem"

        dateFromFilter.placeholder = tr("printJobs.filter.dateFrom")
        dateFromFilter.element.setAttribute(ARIA_LABEL, tr("printJobs.filter.dateFrom"))
        dateFromFilter.isClearButtonVisible = true
        dateFromFilter.width = "10rem"

        dateToFilter.placeholder = tr("printJobs.filter.dateTo")
        dateToFilter.element.setAttribute(ARIA_LABEL, tr("printJobs.filter.dateTo"))
        dateToFilter.isClearButtonVisible = true
        dateToFilter.width = "10rem"

        searchButton = AppButtons.primary(tr(PRINT_JOBS_FILTER_SEARCH_KEY), VaadinIcon.SEARCH) { refreshGrid() }.apply {
            addClassName("print-jobs-search-button")
            element.setAttribute(ARIA_LABEL, tr(PRINT_JOBS_FILTER_SEARCH_KEY))
            element.setAttribute(TITLE_ATTR, tr(PRINT_JOBS_FILTER_SEARCH_KEY))
        }

        resetFilterButton = AppButtons.tertiary(tr(PRINT_JOBS_FILTER_RESET_KEY), VaadinIcon.CLOSE) { clearFilters() }.apply {
            element.setAttribute(ARIA_LABEL, tr(PRINT_JOBS_FILTER_RESET_KEY))
            element.setAttribute(TITLE_ATTR, tr(PRINT_JOBS_FILTER_RESET_KEY))
            isVisible = hasFilterValues()
        }

        // The badge reflects the entered fields; staging a value recounts (one cheap query)
        // but never reloads the grid. Programmatic restores in beforeEnter are skipped.
        listOf(customerFilter, dateFromFilter, dateToFilter).forEach { field ->
            field.addValueChangeListener {
                if (!isInitializing) {
                    resetFilterButton.isVisible = hasFilterValues()
                    refreshNewCountBadge()
                }
            }
        }

        // Enter in any filter field searches like the button. Browser default and propagation stay
        // allowed so an open ComboBox overlay / a typed date is still committed by the component's
        // own Enter handling.
        Shortcuts.addShortcutListener(this, Command { refreshGrid() }, Key.ENTER)
            .listenOn(customerFilter, dateFromFilter, dateToFilter)
            .also {
                it.isBrowserDefaultAllowed = true
                it.isEventPropagationAllowed = true
            }

        return HorizontalLayout(customerFilter, dateFromFilter, dateToFilter, resetFilterButton, searchButton).apply {
            setWidthFull()
            defaultVerticalComponentAlignment = FlexComponent.Alignment.BASELINE
            isSpacing = true
            isPadding = false
            addClassName("toolbar-wrap")
        }
    }

    // ── data provider / current filter ─────────────────────────────────────

    private fun currentFilter(): GenerationJobFilter = GenerationJobFilter(
        recipientId = customerFilter.value?.id,
        createdFrom = dateFromFilter.value?.atStartOfDay(GermanTime.ZONE)?.toOffsetDateTime(),
        createdTo = dateToFilter.value?.atTime(END_OF_DAY_HOUR, END_OF_DAY_MINUTE_SECOND, END_OF_DAY_MINUTE_SECOND)
            ?.atZone(GermanTime.ZONE)?.toOffsetDateTime(),
    )

    private fun hasFilterValues(): Boolean =
        customerFilter.value != null || dateFromFilter.value != null || dateToFilter.value != null

    private fun clearFilters() {
        isInitializing = true
        try {
            customerFilter.value = null
            dateFromFilter.value = null
            dateToFilter.value = null
        } finally {
            isInitializing = false
        }
        resetFilterButton.isVisible = false
        refreshGrid()
    }

    /** [currentFilter] narrowed to one calendar day's own bounds, in [GermanTime.ZONE]: the
     *  row query the TreeGrid's child level uses to load a given day's jobs. Since [day] always
     *  comes from a [PrintJobDaySummary] this same filter already produced, overriding
     *  `createdFrom`/`createdTo` outright (rather than intersecting with whatever the toolbar's
     *  own range filter already set) is always at least as narrow — never wider. */
    private fun dayFilter(day: LocalDate): GenerationJobFilter {
        val start = day.atStartOfDay(GermanTime.ZONE).toOffsetDateTime()
        val end = day.plusDays(1).atStartOfDay(GermanTime.ZONE).toOffsetDateTime().minusNanos(1)
        return currentFilter().copy(createdFrom = start, createdTo = end)
    }

    /**
     * The TreeGrid's hierarchical data provider: root level = [dayNodes] (refreshed by
     * [refreshGrid], never re-queried from inside this class — see [dayNodes]'s own KDoc), child
     * level = one day's jobs via [PrintJobOverviewQueryService.findPaged]/[dayFilter]. Both levels
     * stay lazy/paged; a day with thousands of jobs still only ever loads one page of them.
     */
    private inner class PrintJobTreeDataProvider : AbstractBackEndHierarchicalDataProvider<PrintJobTreeNode, Void>() {

        override fun hasChildren(item: PrintJobTreeNode): Boolean = item is PrintJobTreeNode.DayNode

        override fun getChildCount(query: HierarchicalQuery<PrintJobTreeNode, Void>): Int =
            when (val parent = query.parent) {
                null -> dayNodes.size
                is PrintJobTreeNode.DayNode -> printJobOverviewQueryService.count(dayFilter(parent.summary.day)).toInt()
                else -> 0
            }

        override fun fetchChildrenFromBackEnd(query: HierarchicalQuery<PrintJobTreeNode, Void>): Stream<PrintJobTreeNode> {
            // Explicit `List<PrintJobTreeNode>` typing below: `java.util.stream.Stream<T>` is
            // invariant, so a `Stream<DayNode>`/`Stream<JobNode>` would not otherwise satisfy this
            // override's `Stream<PrintJobTreeNode>` return type even though both are subtypes.
            val page: List<PrintJobTreeNode> = when (val parent = query.parent) {
                null -> dayNodes.drop(query.offset).take(query.limit)
                is PrintJobTreeNode.DayNode -> printJobOverviewQueryService
                    .findPaged(dayFilter(parent.summary.day), query.offset, query.limit)
                    .map { row -> PrintJobTreeNode.JobNode(row) }
                else -> emptyList()
            }
            return page.stream()
        }
    }

    /**
     * Full reload — called only from [beforeEnter] and the Search click/Enter: live updates
     * go through [refreshNewCountBadge] instead. Also resets the "new" badge, since what it counted
     * is part of the freshly loaded rows. Re-fetches the day summaries ([dayNodes]) for the current
     * filter, persists the filter state to the session and refreshes the grid. The most recent day is expanded (per
     * spec) only when it CHANGES ([lastAutoExpandedDay]) — not on every refresh: live-update
     * pushes land several refreshes per job run, and an unconditional expand on each tree data
     * provider refresh was one half of the blank-rows corruption [PrintJobTreeNode.DayNode]'s
     * equality KDoc describes (the day-keyed equality there is the other half). A day the user
     * collapsed also stays collapsed across refreshes this way instead of snapping open again.
     *
     * gridWrapper/emptyState visibility is
     * derived from [dayNodes] itself, the exact list [treeDataProvider]'s root level renders, not
     * from a second, independent [PrintJobOverviewQueryService.count] call. Two separate round
     * trips against a filter this specific (recipient/date-range) could
     * disagree — a row committed between the two calls, or any future filter-semantics drift
     * between `count` and `findDaySummaries` — and show either an empty grid or the empty state
     * over existing data. One query is now the only source of truth for both what the grid shows
     * and whether it is shown at all.
     */
    private fun refreshGrid() {
        if (isInitializing) return
        val filter = currentFilter()
        lastGridLoadAt = time.nowUtc()
        newCountBadge.update(0L)
        persistFilterStateToSession()
        dayNodes = printJobOverviewQueryService.findDaySummaries(filter).map { PrintJobTreeNode.DayNode(it) }
        gridWrapper.isVisible = dayNodes.isNotEmpty()
        emptyState.isVisible = dayNodes.isEmpty()
        emptyState.text = tr("printJobs.empty")
        treeDataProvider.refreshAll()
        val mostRecent = dayNodes.firstOrNull()
        if (mostRecent != null && mostRecent.summary.day != lastAutoExpandedDay) {
            lastAutoExpandedDay = mostRecent.summary.day
            grid.expand(mostRecent)
        }
    }

    // ── session-held filter state ───────────────────────────────────────────

    private fun currentFilterState(): PrintJobListFilterState = PrintJobListFilterState(
        recipientId = customerFilter.value?.id,
        dateFrom = dateFromFilter.value,
        dateTo = dateToFilter.value,
    )

    private fun persistFilterStateToSession() {
        VaadinSession.getCurrent()?.setAttribute(PrintJobListFilterState::class.java, currentFilterState())
    }

    private fun applyFilterState(state: PrintJobListFilterState) {
        state.recipientId?.let { id -> recipientQuery.findRecipientById(id)?.let { customerFilter.value = it } }
        dateFromFilter.value = state.dateFrom
        dateToFilter.value = state.dateTo
    }

    /** Restores the toolbar from the session-held [PrintJobListFilterState] (if any) on every
     *  entry — same "menu navigation carries no query string" reasoning as [InvoiceListView
     *  .restoreFilterState], minus that view's URL round-trip (out of scope here, see
     *  [PrintJobListFilterState]). */
    override fun beforeEnter(event: BeforeEnterEvent) {
        isInitializing = true
        try {
            VaadinSession.getCurrent()?.getAttribute(PrintJobListFilterState::class.java)?.let { applyFilterState(it) }
        } finally {
            isInitializing = false
        }
        resetFilterButton.isVisible = hasFilterValues()
        refreshGrid()
    }
}
