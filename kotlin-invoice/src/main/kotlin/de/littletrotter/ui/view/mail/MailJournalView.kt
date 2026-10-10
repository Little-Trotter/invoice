package de.littletrotter.ui.view.mail

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.grid.GridSortOrder
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.data.provider.SortDirection
import com.vaadin.flow.data.renderer.ComponentRenderer
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.Location
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.QueryParameters
import com.vaadin.flow.router.Route
import com.vaadin.flow.server.VaadinSession
import de.littletrotter.application.export.MailJournalWorkbookExportPort
import de.littletrotter.application.invoice.port.inbound.InvoiceIdByNumberQueryPort
import de.littletrotter.application.mail.MailAvailability
import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.mail.port.inbound.MailJournalCriteria
import de.littletrotter.application.mail.port.inbound.MailJournalEntry
import de.littletrotter.application.mail.port.inbound.MailJournalQueryPort
import de.littletrotter.application.mail.port.inbound.MailJournalSortColumn
import de.littletrotter.application.mail.port.inbound.MailJournalStatus
import de.littletrotter.application.mail.port.inbound.MailSortOrder
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.ui.MainLayout
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.GridWrapper
import de.littletrotter.ui.component.PageShell
import de.littletrotter.ui.component.Pager
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.notification.UiNotifications
import de.littletrotter.ui.view.LIST_PARAM_PAGE
import de.littletrotter.ui.view.LIST_PARAM_SIZE
import de.littletrotter.ui.view.LIST_PARAM_SORT_DIR
import de.littletrotter.ui.view.LIST_PARAM_SORT_FIELD
import de.littletrotter.ui.view.ListPosition
import de.littletrotter.ui.view.invoice.InvoiceListView
import de.littletrotter.ui.view.listQueryParameters
import jakarta.inject.Inject
import kotlin.math.ceil

private val PAGE_SIZES = listOf(25, 50, 100)
private val STATE_PARAM_KEYS = MailJournalFilterBar.FILTER_PARAM_KEYS + setOf(LIST_PARAM_SORT_FIELD, LIST_PARAM_SORT_DIR)

/**
 * The send journal: every mail the component accepted, with its delivery and archive state.
 *
 * Composition root for [MailJournalFilterBar], [MailJournalStatusChips], [MailJournalGridColumns],
 * [MailJournalDetails], [MailJournalRowActions] and the Excel export; the view itself owns paging,
 * sort, the refresh cycle and restoring state on entry. Sorting, filtering and paging all happen in
 * the component: the grid only ever holds one page.
 *
 * Only reachable while the component is part of the instance; otherwise it forwards to the invoice
 * list, the same hidden-not-disabled rule as the drawer entry.
 */
@Route(value = "mail/journal", layout = MainLayout::class)
@PageTitle("E-Mail-Journal")
class MailJournalView
@Inject
constructor(
    private val journalQuery: MailJournalQueryPort,
    rowActions: MailJournalRowActions,
    archiveDownload: MailJournalArchiveDownloadCell,
    invoiceIdByNumber: InvoiceIdByNumberQueryPort,
    exportService: MailJournalWorkbookExportPort,
    private val mailAvailability: MailAvailability,
    time: TimeSource,
) : VerticalLayout(), BeforeEnterObserver, TranslationProvider {

    internal val filterBar = MailJournalFilterBar(this, onFilterChanged = { refreshTable() }, onClearAll = { clearAllFilters() })
    internal val grid = DataTable(MailJournalEntry::class.java)
    internal val statusChips = MailJournalStatusChips(this) { toggleStatusFilter(it) }
    internal val pager = Pager()
    internal val pageSizeSelect: ComboBox<Int> = ComboBox(tr("mail.journal.page.size"))

    private val invoiceLinks = MailJournalInvoiceLinks(invoiceIdByNumber)
    private val details = MailJournalDetails(this)
    private var pageItems: List<MailJournalEntry> = emptyList()
    private var sortColumn = MailJournalSortColumn.CREATED_AT
    private var sortOrder = MailSortOrder.DESC
    private var pageIndex = 0
    private var pageSize = MailJournalCriteria.DEFAULT_PAGE_SIZE
    private var isInitializing = false
    private val liveStatus = Span().apply {
        element.setAttribute("aria-live", "polite")
        element.setAttribute("aria-atomic", "true")
        addClassNames("sr-only")
    }
    private val exportServices = MailJournalExportServices(exportService, { key -> tr(key) }, time)

    private val columns: MailJournalGridColumns

    init {
        setSizeFull()
        isPadding = true
        isSpacing = true

        rowActions.bind(this) { refreshTable(reset = false) }
        archiveDownload.bind(this)
        columns = MailJournalGridColumns(
            translations = this,
            invoiceIdOf = invoiceLinks::invoiceIdOf,
            onRetryOfClick = { entry -> filterBar.referenceKey = entry.referenceKey; refreshTable() },
            actions = rowActions,
            archiveDownload = archiveDownload,
        )
        columns.configure(grid)
        grid.setItemDetailsRenderer(ComponentRenderer { entry: MailJournalEntry -> details.build(entry, pageItems) })
        grid.addSortListener { event ->
            val order = event.sortOrder.firstOrNull()
            val mapped = order?.let { MailJournalGridColumns.sortColumnForKey(it.sorted.key) }
            sortColumn = mapped ?: MailJournalSortColumn.CREATED_AT
            sortOrder = if (mapped != null && order.direction == SortDirection.ASCENDING) MailSortOrder.ASC else MailSortOrder.DESC
            refreshTable()
        }
        pager.onPageChange { page, size ->
            pageIndex = page
            pageSize = size
            refreshTable(reset = false)
        }
        pageSizeSelect.setItems(PAGE_SIZES)
        pageSizeSelect.value = pageSize.takeIf { it in PAGE_SIZES } ?: PAGE_SIZES[1]
        pageSizeSelect.addValueChangeListener { event ->
            val newSize = event.value ?: pageSize
            if (newSize != pageSize) {
                pageSize = newSize
                pager.setPageSize(newSize)
                refreshTable()
            }
        }

        val gridWrapper = GridWrapper(grid)
        gridWrapper.setSizeFull()
        val export = buildMailJournalExportButton(::currentCriteria, exportServices)
        val shell = PageShell(tr("nav.mailJournal"))
            .actions(export)
            .filters(buildToolbar())
            .content(statusChips, liveStatus, gridWrapper)
        shell.setSizeFull()
        add(shell)
        expand(shell)
    }

    override fun getComponent(): Component = this

    private fun buildToolbar(): Div {
        val spacer = Div()
        val row = HorizontalLayout(filterBar.textField, filterBar.fromPicker, filterBar.toPicker, spacer, pager, pageSizeSelect).apply {
            setWidthFull()
            defaultVerticalComponentAlignment = Alignment.BASELINE
            setSpacing(true)
            isPadding = false
            addClassName("toolbar-wrap")
        }
        row.expand(spacer)
        return Div(row, filterBar.buildFilterRow()).apply { addClassNames("ino-toolbar", "toolbar-full") }
    }

    private fun clearAllFilters() {
        isInitializing = true
        try {
            filterBar.clear()
        } finally {
            isInitializing = false
        }
        refreshTable()
    }

    private fun toggleStatusFilter(status: MailJournalStatus) {
        filterBar.statusFilter = if (filterBar.statusFilter == status) null else status
        refreshTable()
    }

    private fun currentCriteria(): MailJournalCriteria =
        filterBar.criteria().copy(sort = sortColumn, direction = sortOrder, page = pageIndex, size = pageSize)

    // ── refresh cycle ─────────────────────────────────────────────────────────

    private fun refreshTable(reset: Boolean = true) {
        if (isInitializing) return
        if (reset) pageIndex = 0
        invoiceLinks.reset()
        try {
            load()
        } catch (e: MailDispatchException) {
            // The component is the only source of rows; showing stale ones as current would be worse than none.
            pageItems = emptyList()
            grid.setItems(pageItems)
            UiNotifications.error(tr("mail.journal.loadFailed", e.message.orEmpty()))
        }
        VaadinSession.getCurrent().setAttribute(MailJournalFilterState::class.java, filterBar.state())
        filterBar.refreshActiveFilterChip()
    }

    private fun load() {
        val criteria = filterBar.criteria()
        val total = journalQuery.count(criteria)
        val totalPages = maxOf(1, ceil(total.toDouble() / pageSize.toDouble()).toInt())
        pageIndex = pageIndex.coerceIn(0, totalPages - 1)
        pageItems = journalQuery.findPage(currentCriteria())
        invoiceLinks.load(pageItems.map { it.referenceKey })
        grid.setItems(pageItems)
        // The chips ignore the status filter themselves, so a selected chip keeps its count and can be un-toggled.
        statusChips.render(journalQuery.countByStatus(criteria), filterBar.statusFilter)
        pager.setTotalItems(total.toInt())
        pager.setPageSize(pageSize)
        pager.goToPage(pageIndex, fireListener = false)
        pageSizeSelect.isEnabled = total > PAGE_SIZES.first()
        liveStatus.text = tr("mail.journal.pageStatus", pageIndex + 1, totalPages, total)
    }

    // ── URL and session state ─────────────────────────────────────────────────

    internal fun currentQueryParameters(): QueryParameters =
        listQueryParameters(filterBar::writeQueryParameters, ListPosition(pageIndex, pageSize, sortColumn, sortOrder))

    override fun beforeEnter(event: BeforeEnterEvent) {
        if (!mailAvailability.enabled) {
            event.forwardTo(InvoiceListView::class.java)
            return
        }
        restoreState(event.location.queryParameters)
    }

    /**
     * An explicit URL parameter always wins (deep links, `?reference=`). Only a URL without any
     * filter or sort parameter lets the session's [MailJournalFilterState] step in, reflected back
     * into the address bar. Paging is never session state, but an explicit URL page/size is kept.
     */
    internal fun restoreState(queryParameters: QueryParameters) {
        val urlPage = queryParameters.parameters[LIST_PARAM_PAGE]?.firstOrNull()?.toIntOrNull()?.let { maxOf(0, it) }
        isInitializing = true
        try {
            sortColumn = MailJournalSortColumn.CREATED_AT
            sortOrder = MailSortOrder.DESC
            filterBar.clear()
            if (STATE_PARAM_KEYS.any { queryParameters.parameters.containsKey(it) }) {
                filterBar.applyQueryParameters(queryParameters)
                applySortParameters(queryParameters)
            } else {
                VaadinSession.getCurrent().getAttribute(MailJournalFilterState::class.java)?.let {
                    filterBar.applyState(it)
                    UI.getCurrent().page.history.replaceState(null, Location("", currentQueryParameters()).pathWithQueryParameters)
                }
            }
            pageIndex = urlPage ?: 0
            queryParameters.parameters[LIST_PARAM_SIZE]?.firstOrNull()?.toIntOrNull()?.takeIf { it in PAGE_SIZES }?.let {
                pageSize = it
                pageSizeSelect.value = it
                pager.setPageSize(it)
            }
            grid.getColumnByKey(MailJournalGridColumns.keyForSortColumn(sortColumn))?.let { column ->
                val direction = if (sortOrder == MailSortOrder.ASC) SortDirection.ASCENDING else SortDirection.DESCENDING
                grid.sort(listOf(GridSortOrder(column, direction)))
            }
        } finally {
            isInitializing = false
        }
        refreshTable(reset = urlPage == null)
    }

    /** An unknown sortField/sortDir comes from an edited or stale bookmark, not an app bug: the default stays. */
    private fun applySortParameters(queryParameters: QueryParameters) {
        queryParameters.parameters[LIST_PARAM_SORT_FIELD]?.firstOrNull()?.let { name ->
            MailJournalSortColumn.entries.find { it.name == name }?.let { sortColumn = it }
        }
        queryParameters.parameters[LIST_PARAM_SORT_DIR]?.firstOrNull()?.let { name ->
            MailSortOrder.entries.find { it.name == name }?.let { sortOrder = it }
        }
    }
}
