package de.littletrotter.ui.view.invoice

import de.littletrotter.application.dto.InvoiceExportFilter
import de.littletrotter.application.dto.InvoiceOverviewDto
import de.littletrotter.application.dto.InvoiceOverviewSortField
import de.littletrotter.application.dto.SortDirection
import de.littletrotter.application.invoice.InvoiceOverviewSort
import de.littletrotter.application.invoice.port.inbound.InvoiceListPage
import de.littletrotter.application.invoice.port.inbound.InvoiceListQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import kotlin.math.ceil

/** Component state a list refresh depends on, read under the session lock so the queries can run
 *  without it. Equality doubles as the staleness check: a result is only applied while the view
 *  still shows exactly this state. */
internal data class InvoiceListQuery(
    val filter: InvoiceExportFilter,
    val pageIndex: Int,
    val pageSize: Int,
    val sortField: InvoiceOverviewSortField,
    val sortDir: SortDirection,
    val statusFilter: InvoiceStatus?,
)

/** Immutable outcome of the list queries: everything the components need, nothing that touches them. */
internal data class InvoiceListLoad(
    val query: InvoiceListQuery,
    val pageIndex: Int,
    val totalPages: Int,
    val total: Long,
    val items: List<InvoiceOverviewDto>,
    val statusCounts: Map<InvoiceStatus, Long>,
)

/** Runs the list queries for [query]; the page index is clamped to the result's last page, so a
 *  shrunken result set never leaves the view on an empty page. */
internal fun loadInvoiceList(port: InvoiceListQueryPort, query: InvoiceListQuery): InvoiceListLoad {
    val total = port.count(query.filter)
    val totalPages = if (total <= 0L) 1 else maxOf(1, ceil(total.toDouble() / query.pageSize.toDouble()).toInt())
    val pageIndex = query.pageIndex.coerceIn(0, totalPages - 1)
    val items = port.findPage(
        query.filter,
        InvoiceListPage(pageIndex, query.pageSize, InvoiceOverviewSort(query.sortField, query.sortDir)),
    )
    // The chip counts cover every state reachable under the other filters, so the status filter
    // itself is never part of the grouped-count query.
    return InvoiceListLoad(query, pageIndex, totalPages, total, items, port.countByStatus(query.filter))
}
