package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.grid.ColumnTextAlign
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.grid.GridSortOrder
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.NativeButton
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.provider.SortDirection
import com.vaadin.flow.data.value.ValueChangeMode
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import de.littletrotter.application.quote.port.inbound.QuoteAvailabilityQueryPort
import de.littletrotter.application.quote.port.inbound.CreateQuoteUseCase
import de.littletrotter.application.quote.port.inbound.QuoteCustomerLookupQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteListCriteria
import de.littletrotter.application.quote.port.inbound.QuoteListQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteSortDirection
import de.littletrotter.application.quote.port.inbound.QuoteSortField
import de.littletrotter.application.quote.port.inbound.QuoteStatisticsQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.application.quote.port.inbound.QuoteSummary
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.port.inbound.AppRoleQueryPort
import de.littletrotter.ui.MainLayout
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.ChipTone
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.GridWrapper
import de.littletrotter.ui.component.PageShell
import de.littletrotter.ui.component.PageSizeSelect
import de.littletrotter.ui.component.Pager
import de.littletrotter.ui.component.StatusQuickFilterRow
import de.littletrotter.ui.component.applyChipTone
import de.littletrotter.ui.component.asNumericColumn
import de.littletrotter.ui.i18n.TranslationProvider
import jakarta.inject.Inject
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.asTextColumn

/**
 * The offer list: paged by the offer component, filtered by status and free text. A click on a row
 * opens the offer; "Neues Angebot" starts a draft for a customer and continues in the editor.
 *
 * Only reachable while the offer component is part of the instance and the caller may view offers;
 * otherwise it forwards to the invoice list.
 */
@Route(value = QuoteRoutes.LIST, layout = MainLayout::class)
@PageTitle("Angebote")
class QuoteListView
@Inject
constructor(
    private val listQuery: QuoteListQueryPort,
    private val exports: QuoteListExportPorts,
    private val statisticsQuery: QuoteStatisticsQueryPort,
    customerLookup: QuoteCustomerLookupQueryPort,
    createUseCase: CreateQuoteUseCase,
    availability: QuoteAvailabilityQueryPort,
    roles: AppRoleQueryPort,
) : VerticalLayout(), BeforeEnterObserver, TranslationProvider {

    internal val grid = DataTable(QuoteSummary::class.java)
    internal val pager = Pager()
    internal val searchField = TextField()
    internal val resetChip = NativeButton().apply {
        applyChipTone(ChipTone.NEUTRAL)
        addClassName("quick-filter-chip")
        isVisible = false
    }
    internal val emptyState = Span(tr("quote.list.empty"))
    internal val pageSizeSelect = PageSizeSelect(tr("quote.list.pageSize"), QuoteListCriteria.DEFAULT_LIMIT) { size ->
        if (size != pageSize) {
            pageSize = size
            pager.setPageSize(size)
            refresh()
        }
    }
    internal val newButton = AppButtons.primary(tr("quote.list.new"), ActionIcons.ADD) { createDialog.show() }

    private val access = QuoteAccess(availability, roles)
    private val labels = QuoteLabels(this)
    private val createDialog = QuoteCreateDialog(this, customerLookup, createUseCase) { id ->
        // The create use case answers with the technical id; the edit route forwards it to the number route.
        UI.getCurrent().navigate(QuoteRoutes.edit(id))
    }
    internal val quickFilterRow = StatusQuickFilterRow(
        QuoteStatus.known,
        tr("quote.quickFilter.ariaLabel"),
        labels::quickFilterSpec,
    ) { toggleStatus(it) }
    private var pageIndex = 0
    private var pageSize = QuoteListCriteria.DEFAULT_LIMIT
    private var sortField: QuoteSortField = QuoteListSort.DEFAULT.first
    private var sortDirection: QuoteSortDirection = QuoteListSort.DEFAULT.second
    private var markingSort = false

    /** What the list shows right now; the export reads it when the download runs, possibly off the UI thread. */
    @Volatile
    private var shownCriteria = QuoteListCriteria()
    private var activeStatus: QuoteStatus? = null

    override fun getComponent(): Component = this

    init {
        setSizeFull()
        isPadding = true
        isSpacing = true
        configureControls()
        configureColumns()
        pager.setPageSize(pageSize)
        pager.onPageChange { page, _ ->
            pageIndex = page
            refresh(resetPage = false)
        }
        emptyState.addClassName("text-muted")
        val gridWrapper = GridWrapper(grid)
        gridWrapper.setSizeFull()
        val exportButton = buildQuoteListExportButton(this, exports, { shownCriteria }) { criteria ->
            guarded { listQuery.findPage(criteria.copy(offset = 0, limit = 1)) }?.total ?: 0L
        }
        val shell = PageShell(tr("nav.section.quoting"))
            .actions(exportButton, newButton)
            .filters(buildToolbar(), buildQuickFilters())
            .content(emptyState, gridWrapper)
        shell.setSizeFull()
        add(shell)
        expand(shell)
    }

    private fun configureControls() {
        resetChip.addClickListener { resetFilters() }
        searchField.placeholder = tr("quote.list.filter.text")
        searchField.prefixComponent = VaadinIcon.SEARCH.create()
        searchField.element.setAttribute("aria-label", tr("quote.list.filter.text"))
        searchField.width = "18rem"
        searchField.isClearButtonVisible = true
        searchField.valueChangeMode = ValueChangeMode.LAZY
        searchField.addValueChangeListener { refresh() }
    }

    private fun buildToolbar(): Div {
        val spacer = Div()
        val row = HorizontalLayout(searchField, spacer, pager, pageSizeSelect, resetChip).apply {
            setWidthFull()
            defaultVerticalComponentAlignment = Alignment.BASELINE
            isPadding = false
            addClassName("toolbar-wrap")
        }
        row.expand(spacer)
        return Div(row).apply { addClassNames("ino-toolbar", "toolbar-full") }
    }

    private fun buildQuickFilters(): HorizontalLayout = HorizontalLayout(quickFilterRow).apply {
        setWidthFull()
        isPadding = false
        defaultVerticalComponentAlignment = Alignment.CENTER
        addClassName("toolbar-wrap")
    }

    /** The same status again switches the filter off: one active chip at most. */
    private fun toggleStatus(status: QuoteStatus) {
        activeStatus = if (activeStatus == status) null else status
        refresh()
    }

    private fun resetFilters() {
        activeStatus = null
        if (searchField.value.isEmpty()) refresh() else searchField.clear()
    }

    private fun configureColumns() {
        grid.setSizeFull()
        grid.element.setAttribute("aria-label", tr("quote.list.table.ariaLabel"))
        grid.addColumn({ it.requireNumber() }).setHeader(tr("quote.column.number")).setKey(QuoteListSort.KEY_NUMBER).apply {
            serverSorted()
            asCompactColumn()
        }
        grid.addColumn({ it.customerName }).setHeader(tr("quote.column.customer")).setKey(QuoteListSort.KEY_CUSTOMER).apply {
            serverSorted()
            asTextColumn(TextColumnWidth.MEDIUM)
        }
        grid.addColumn({ it.subject.orEmpty() }).setHeader(tr("quote.column.subject")).setKey(QuoteListSort.KEY_SUBJECT).apply {
            serverSorted()
            asTextColumn(TextColumnWidth.LARGE)
        }
        grid.addComponentColumn { labels.listStatusGlyph(it.status, it.validUntil, exports.time.today()) }.setHeader(buildQuoteStatusColumnHeader({ key -> tr(key) }) { openQuoteStatusLegend { key -> tr(key) } }).setKey(QuoteListSort.KEY_STATUS).apply {
            serverSorted()
            asCompactColumn()
            textAlign = ColumnTextAlign.CENTER
        }
        grid.addColumn({ labels.date(it.quoteDate) }).setHeader(tr("quote.column.date")).setKey(QuoteListSort.KEY_DATE).apply {
            serverSorted()
            asCompactColumn()
        }
        grid.addColumn({ labels.date(it.validUntil) }).setHeader(tr("quote.column.validUntil")).setKey(QuoteListSort.KEY_VALID_UNTIL).apply {
            serverSorted()
            asCompactColumn()
        }
        grid.addColumn({ labels.money(it.netTotal) }).setHeader(tr("quote.column.net")).setKey(QuoteListSort.KEY_NET).apply {
            serverSorted()
            asNumericColumn()
        }
        // The grid only shows the page the offer component sorted; its own in-memory order must not reshuffle it.
        grid.addSortListener { event ->
            if (markingSort) return@addSortListener
            val (field, direction) = QuoteListSort.fromGrid(event.sortOrder)
            sortField = field
            sortDirection = direction
            refresh()
        }
        // A click outside any row (or on a row that vanished after a refresh) carries no item.
        grid.addItemClickListener { event -> event.item?.let { UI.getCurrent().navigate(QuoteRoutes.detail(it.requireNumber())) } }
    }

    override fun beforeEnter(event: BeforeEnterEvent) {
        if (!access.admit(event, AppPermission.QUOTE_VIEW)) return
        newButton.isVisible = access.mayEdit()
        markActiveSortColumn()
        refresh()
    }

    /** Shows the applied order in the column header, as the invoice list does; the refresh that follows loads it. */
    private fun markActiveSortColumn() {
        val column = grid.getColumnByKey(QuoteListSort.keyForField(sortField)) ?: return
        val direction = if (sortDirection == QuoteSortDirection.ASC) SortDirection.ASCENDING else SortDirection.DESCENDING
        markingSort = true
        try {
            grid.sort(listOf(GridSortOrder(column, direction)))
        } finally {
            markingSort = false
        }
    }

    private fun refresh(resetPage: Boolean = true) {
        if (resetPage) pageIndex = 0
        val criteria = QuoteListCriteria(
            status = activeStatus,
            text = searchField.value.trim().ifEmpty { null },
            offset = pageIndex * pageSize,
            limit = pageSize,
            sortField = sortField,
            sortDirection = sortDirection,
        )
        shownCriteria = criteria
        val page = guarded { listQuery.findPage(criteria) }
        // The component is the only source of rows; stale ones shown as current would be worse than none.
        grid.setItems(page?.rows.orEmpty())
        emptyState.isVisible = page?.rows.isNullOrEmpty()
        pager.setTotalItems((page?.total ?: 0L).toInt())
        pageSizeSelect.isEnabled = (page?.total ?: 0L) > pageSize
        pager.goToPage(pageIndex, fireListener = false)
        val stats = guarded { statisticsQuery.statistics() }
        quickFilterRow.render(stats?.countsByStatus.orEmpty(), activeStatus)
        val activeFilters = listOf(activeStatus != null, searchField.value.isNotBlank()).count { it }
        resetChip.isVisible = activeFilters > 0
        resetChip.text = tr("quote.list.filter.active", activeFilters)
    }
}

private val KEEP_SERVER_ORDER = Comparator<QuoteSummary> { _, _ -> 0 }

/** Makes the column sortable by the offer component; the grid's own in-memory sort keeps the page as delivered. */
private fun Grid.Column<QuoteSummary>.serverSorted() {
    isSortable = true
    setComparator(KEEP_SERVER_ORDER)
}
