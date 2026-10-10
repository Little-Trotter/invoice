package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.AttachEvent
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.DetachEvent
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.grid.GridSortOrder
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.Location
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.QueryParameters
import com.vaadin.flow.router.Route
import com.vaadin.flow.router.RouteParameters
import com.vaadin.flow.server.VaadinSession
import de.littletrotter.application.dto.InvoiceExportFilter
import de.littletrotter.application.dto.InvoiceOverviewDto
import de.littletrotter.application.dto.InvoiceOverviewSortField
import de.littletrotter.application.dto.SortDirection
import de.littletrotter.application.export.InvoiceWorkbookExportPort
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceListQueryPort
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.ui.MainLayout
import de.littletrotter.ui.UiEventBus
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.GridWrapper
import de.littletrotter.ui.component.PageSizeSelect
import de.littletrotter.ui.component.PageShell
import de.littletrotter.ui.component.Pager
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.view.LIST_PARAM_PAGE
import de.littletrotter.ui.view.LIST_PARAM_SIZE
import de.littletrotter.ui.view.LIST_PARAM_SORT_DIR
import de.littletrotter.ui.view.LIST_PARAM_SORT_FIELD
import de.littletrotter.ui.view.ListPosition
import de.littletrotter.ui.view.listQueryParameters
import jakarta.inject.Inject
import org.jboss.logging.Logger
import de.littletrotter.ui.component.ActionIcons

/**
 * The invoice list: composition root for [InvoiceListFilterBar] (filter fields and their
 * URL/session representations), [invoiceQuickFilterRow] (per-state count chips) and
 * [InvoiceListGridColumns] (columns and cells), [InvoiceListNavigator] (detail navigation) and
 * [InvoiceListLiveUpdates] (event bus + poll fallback). The view itself owns paging, sort, the
 * refresh cycle and the filter-state restore on entry.
 */
@Route(value = "", layout = MainLayout::class)
@PageTitle("Rechnungen")
class InvoiceListView
@Inject
constructor(
    // Every collaborator injects only the narrow input port it uses.
    private val recipientQuery: RecipientQueryPort,
    private val senderQuery: SenderQueryPort,
    private val createInvoiceUseCase: CreateInvoiceUseCase,
    private val invoiceListQuery: InvoiceListQueryPort,
    private val uiEventBus: UiEventBus,
    // The filtered-list export's backing service; `null` (component tests that don't
    // consider export) means the export button is not built. Production CDI always resolves it.
    private val invoiceWorkbookExportService: InvoiceWorkbookExportPort? = null,
    private val time: TimeSource,
) : VerticalLayout(), BeforeEnterObserver, TranslationProvider {

    private val log = Logger.getLogger(javaClass)

    internal val filterBar = InvoiceListFilterBar(
        recipientQuery = recipientQuery,
        senderQuery = senderQuery,
        invoiceListQuery = invoiceListQuery,
        translations = this,
        onFilterChanged = { refreshTable() },
        onClearAll = { clearAllFilters() },
    )

    private val grid = DataTable(InvoiceOverviewDto::class.java)
    private val navigator = InvoiceListNavigator(
        recipientQuery = recipientQuery,
        senderQuery = senderQuery,
        createInvoiceUseCase = createInvoiceUseCase,
        invoiceListQuery = invoiceListQuery,
        translations = this,
        currentQueryParameters = { currentQueryParameters() },
        onStaleRow = { refreshTable(reset = false) },
    )
    internal val gridColumns = InvoiceListGridColumns(this) { invoiceId -> navigator.openValidation(invoiceId) }
    private var currentSortField: InvoiceOverviewSortField = InvoiceOverviewSortField.INVOICE_DATE
    private var currentSortDir: SortDirection = SortDirection.DESC

    private val quickFilterRow = invoiceQuickFilterRow(this) { st -> toggleStatusFilter(st) }

    private var currentPageIndex: Int = 0
    private var pageSize: Int = PageSizeSelect.DEFAULT_SIZE
    private val pager: Pager = Pager()
    private val pageSizeSelect = PageSizeSelect(tr("invoice.page.size"), pageSize) { newSize ->
        if (newSize != pageSize) {
            pageSize = newSize
            pager.setPageSize(newSize)
            refreshTable()
        }
    }
    internal val emptyState = Span(tr("invoice.list.empty")).apply {
        addClassName("text-muted")
        isVisible = false
    }
    private val liveStatus = Span().apply {
        element.setAttribute("aria-live", "polite")
        element.setAttribute("aria-atomic", "true")
        addClassNames("sr-only")
    }

    private var isRefreshing = false
    private var isInitializing = false

    private val liveUpdates = InvoiceListLiveUpdates(
        uiEventBus = uiEventBus,
        isViewAttached = { isAttached },
        capture = ::captureListQuery,
        load = { query -> loadInvoiceList(invoiceListQuery, query) },
        hasPendingValidation = { filter -> hasPendingValidationInResultSet(filter) },
        apply = ::applyOffLock,
    )

    init {
        setSizeFull()
        isPadding = true
        isSpacing = true

        configureGrid()
        pager.onPageChange { page, size ->
            currentPageIndex = page
            pageSize = size
            refreshTable(false)
        }

        val gridWrapper = GridWrapper(grid).withPager(pager)
        gridWrapper.setSizeFull()

        val shell = PageShell(tr("nav.invoices")).apply {
            actions(*listOfNotNull(buildExportButton(), buildNewInvoiceButton()).toTypedArray())
            filters(buildToolbar())
            content(quickFilterRow, liveStatus, emptyState, gridWrapper)
        }
        shell.setSizeFull()
        add(shell)
        expand(shell)
        updatePageTitle()
    }

    private fun updatePageTitle() {
        val ui = UI.getCurrent() ?: return
        ui.page.setTitle("${tr("nav.invoices")} | ${tr("app.title")}")
    }

    override fun getComponent(): Component = this

    private fun configureGrid() {
        gridColumns.configure(grid)
        grid.addItemClickListener { event -> event.item?.let { navigateToDetail(it) } }
        grid.addSortListener { event ->
            val order = event.sortOrder.firstOrNull()
            if (order == null) {
                currentSortField = InvoiceOverviewSortField.INVOICE_DATE
                currentSortDir = SortDirection.DESC
            } else {
                currentSortField = InvoiceListGridColumns.sortFieldForColumnKey(order.sorted.key)
                currentSortDir = if (order.direction == com.vaadin.flow.data.provider.SortDirection.ASCENDING) {
                    SortDirection.ASC
                } else {
                    SortDirection.DESC
                }
            }
            refreshTable()
        }
    }

    // ── Toolbar ───────────────────────────────────────────────────────────────

    private fun buildNewInvoiceButton(): Button =
        AppButtons.primary(tr("invoice.new"), ActionIcons.ADD) { openInvoiceCreateDialog() }.apply {
            element.setAttribute("aria-label", tr("invoice.new"))
        }

    /** Acts on the whole filtered set; lives in the page header next to the other actions. */
    private fun buildExportButton(): Component? =
        invoiceWorkbookExportService?.let { service ->
            buildInvoiceListExportButton(
                currentFilter = filterBar::currentFilter,
                currentScopeDescription = filterBar::currentScopeDescription,
                countMatches = ::countExportMatches,
                services = ExportServices(
                    service = service,
                    tr = { key -> tr(key) },
                    trWithArg = { key, arg -> tr(key, arg) },
                    time = time,
                ),
            )
        }

    private fun buildToolbar(): Div {
        // Row 1 "Finden": text search left, a flexed spacer pushes the year quick pick and
        // paging cluster to the right end; the new-invoice action lives in the page header.
        val row1Spacer = Div()
        val row1Components: List<Component> = listOfNotNull(
            filterBar.filterField, row1Spacer, filterBar.yearQuickPick, pager, pageSizeSelect,
        )
        val row1 = HorizontalLayout(*row1Components.toTypedArray()).apply {
            setWidthFull()
            defaultVerticalComponentAlignment = Alignment.BASELINE
            setSpacing(true)
            isPadding = false
            addClassName("toolbar-wrap")
        }
        row1.expand(row1Spacer)

        return Div(row1, filterBar.buildFilterRow()).apply {
            addClassNames("ino-toolbar", "toolbar-full")
        }
    }

    /** The reset chip's click target — clears every filter with intermediate refreshes suppressed,
     *  then refreshes once: back to page 1 and, via [persistFilterStateToSession], an empty session
     *  state ("clearing filters clears the session too"). */
    private fun clearAllFilters() {
        isInitializing = true
        try {
            filterBar.clear()
        } finally {
            isInitializing = false
        }
        refreshTable()
    }

    private fun toggleStatusFilter(st: InvoiceStatus) {
        filterBar.statusFilter = if (filterBar.statusFilter == st) null else st
        refreshTable()
    }

    // ── live updates ──────────────────────────────────────────────────────────

    override fun onAttach(attachEvent: AttachEvent) {
        super.onAttach(attachEvent)
        liveUpdates.attach(attachEvent.ui)
    }

    override fun onDetach(detachEvent: DetachEvent?) {
        super.onDetach(detachEvent)
        liveUpdates.detach(detachEvent?.ui)
    }

    /** Whether the current filter's result set, narrowed to [ValidationStatus.PENDING], still has
     *  an invoice — the poll fallback's activity signal. A validation filter for a terminal state
     *  can never show a PENDING row, so it reports "nothing active" without a query. */
    private fun hasPendingValidationInResultSet(f: InvoiceExportFilter): Boolean =
        (f.validationStatus == null || f.validationStatus == ValidationStatus.PENDING) &&
            invoiceListQuery.count(f.copy(validationStatus = ValidationStatus.PENDING)) > 0

    // ── refresh cycle ─────────────────────────────────────────────────────────

    /** Explicit user actions (filter, sort, paging, entry) refresh synchronously from the same
     *  capture/load/apply functions the off-lock live-update path uses. */
    private fun refreshTable(reset: Boolean = true) {
        if (isRefreshing || isInitializing) {
            return
        }

        isRefreshing = true
        try {
            if (reset) {
                currentPageIndex = 0
            }
            grid.setItems(emptyList<InvoiceOverviewDto>())
            applyList(loadInvoiceList(invoiceListQuery, captureListQuery()))
        } finally {
            isRefreshing = false
        }
    }

    /** Reads the component state the list queries depend on; no I/O. */
    private fun captureListQuery() = InvoiceListQuery(
        filter = filterBar.currentFilter(),
        pageIndex = currentPageIndex,
        pageSize = pageSize,
        sortField = currentSortField,
        sortDir = currentSortDir,
        statusFilter = filterBar.statusFilter,
    )

    /** Renders [load] into grid, chips and pager; components only, no queries. */
    private fun applyList(load: InvoiceListLoad) {
        currentPageIndex = load.pageIndex
        // Probe: the DataCommunicator->client hand-off order, visible at DEBUG only.
        log.debugf("overview page %d hand-off order: %s", currentPageIndex, load.items.map { it.id })
        grid.setItems(load.items)
        emptyState.isVisible = load.items.isEmpty()
        quickFilterRow.render(load.statusCounts, filterBar.statusFilter)

        pager.setTotalItems(load.total.toInt())
        pager.setPageSize(pageSize)
        // fireListener = false avoids a recursive refresh through onPageChange
        pager.goToPage(currentPageIndex, fireListener = false)
        pageSizeSelect.isEnabled = load.total > pageSize
        liveStatus.text = tr("invoice.accessibility.pageStatus", currentPageIndex + 1, load.totalPages, load.total)

        // Every successful refresh snapshots the filter state into the session so a later
        // visit without URL parameters (menu navigation) can restore it.
        persistFilterStateToSession()
        filterBar.refreshActiveFilterChip()
    }

    /** Off-lock result delivery: dropped while a synchronous refresh is running or when the user
     *  changed filter, sort or paging during the load — the newer state refreshes itself. */
    private fun applyOffLock(load: InvoiceListLoad): Boolean {
        if (isRefreshing || isInitializing || captureListQuery() != load.query) return false
        applyList(load)
        return true
    }

    private fun countExportMatches(filter: InvoiceExportFilter): Long = invoiceListQuery.count(filter)

    private fun persistFilterStateToSession() {
        VaadinSession.getCurrent()?.setAttribute(InvoiceListFilterState::class.java, filterBar.currentFilterState())
    }

    /** Reflects the current state into the browser URL via `history.replaceState`, without a
     *  navigation. Only needed after a session restore; an explicit URL already reflects itself. */
    private fun reflectCurrentStateIntoUrl() {
        val ui = UI.getCurrent() ?: return
        val queryParameters = currentQueryParameters()
        val location = Location("", queryParameters)
        ui.page.history.replaceState(null, location.pathWithQueryParameters)
    }

    // ── navigation ────────────────────────────────────────────────────────────

    private fun navigateToDetail(inv: InvoiceOverviewDto) = navigator.openRow(inv)

    private fun openInvoiceCreateDialog() = navigator.openCreateDialog()

    internal fun buildDetailNavigationTarget(id: Long, focus: String?): Pair<RouteParameters, QueryParameters> =
        navigator.detailTarget(id, focus)

    /** The filter, paging and sort state as URL query parameters (deep links, "back to list"). */
    private fun currentQueryParameters(): QueryParameters =
        listQueryParameters(filterBar::writeQueryParameters, ListPosition(currentPageIndex, pageSize, currentSortField, currentSortDir))

    // ── restore filter/sort state on entry ────────────────────────────────────

    override fun beforeEnter(event: BeforeEnterEvent) = restoreFilterState(event.location.queryParameters)

    /**
     * Restore rule (PO fix 2026-08-25): an explicit URL query parameter always wins (deep links).
     * Only when the URL carries none of [FILTER_PARAM_KEYS] (e.g. the menu link) does the
     * session-held [InvoiceListFilterState] step in, reflected back into the URL so the address
     * bar stays truthful. `internal` so tests can drive it with a hand-built [QueryParameters].
     */
    internal fun restoreFilterState(queryParameters: QueryParameters) {
        var urlPageIndex: Int?
        isInitializing = true
        try {
            // The router reuses this instance list→list; a header-click sort must not
            // survive into the newest-first entry default. Only an explicit URL sort overrides it.
            currentSortField = InvoiceOverviewSortField.INVOICE_DATE
            currentSortDir = SortDirection.DESC
            val hasRelevantUrlParams = FILTER_PARAM_KEYS.any { queryParameters.parameters.containsKey(it) }
            if (hasRelevantUrlParams) {
                filterBar.applyQueryParameters(queryParameters)
                applySortParameters(queryParameters)
            } else {
                VaadinSession.getCurrent()?.getAttribute(InvoiceListFilterState::class.java)?.let { sessionState ->
                    filterBar.applyFilterState(sessionState)
                    reflectCurrentStateIntoUrl()
                }
            }
            filterBar.refreshYearQuickPickFromDateRange()

            // Paging is never session state (a stale page is worse than page 1), but an explicit
            // URL page/size still wins.
            urlPageIndex = queryParameters.parameters[LIST_PARAM_PAGE]?.firstOrNull()?.toIntOrNull()
                ?.let { maxOf(0, it) }
            urlPageIndex?.let { currentPageIndex = it }
            queryParameters.parameters[LIST_PARAM_SIZE]?.firstOrNull()?.toIntOrNull()?.let {
                val validatedSize = if (it <= 0) PageSizeSelect.DEFAULT_SIZE else it
                pageSize = validatedSize
                pageSizeSelect.value = validatedSize
                pager.setPageSize(validatedSize)
            }

            val sortColumnKey = InvoiceListGridColumns.columnKeyForSortField(currentSortField)
            grid.getColumnByKey(sortColumnKey)?.let { column ->
                val vaadinSortDir = if (currentSortDir == SortDirection.ASC) {
                    com.vaadin.flow.data.provider.SortDirection.ASCENDING
                } else {
                    com.vaadin.flow.data.provider.SortDirection.DESCENDING
                }
                grid.sort(listOf(GridSortOrder(column, vaadinSortDir)))
            }
        } finally {
            isInitializing = false
        }

        // Keep the restored page exactly when the URL carried one; every other entry resets.
        refreshTable(reset = urlPageIndex == null)
    }

    /** An unknown sortField/sortDir comes from an edited or stale bookmark, not an app bug: the
     *  default stays and the value is logged at debug. */
    private fun applySortParameters(queryParameters: QueryParameters) {
        queryParameters.parameters[LIST_PARAM_SORT_FIELD]?.firstOrNull()?.let { sortFieldName ->
            try {
                currentSortField = InvoiceOverviewSortField.valueOf(sortFieldName)
            } catch (e: IllegalArgumentException) {
                log.debugf("Ignoring unknown sortField query parameter '%s': %s", sortFieldName, e.message)
            }
        }
        queryParameters.parameters[LIST_PARAM_SORT_DIR]?.firstOrNull()?.let { sortDirName ->
            try {
                currentSortDir = SortDirection.valueOf(sortDirName)
            } catch (e: IllegalArgumentException) {
                log.debugf("Ignoring unknown sortDir query parameter '%s': %s", sortDirName, e.message)
            }
        }
    }

    companion object {
        /** Keys that count as "the URL carries explicit filter state" — deliberately excludes
         *  `page`/`size` (never persisted) and `focus` ([InvoiceDetailView]'s own concern). */
        private val FILTER_PARAM_KEYS = InvoiceListFilterBar.FILTER_PARAM_KEYS + setOf(LIST_PARAM_SORT_FIELD, LIST_PARAM_SORT_DIR)
    }
}
