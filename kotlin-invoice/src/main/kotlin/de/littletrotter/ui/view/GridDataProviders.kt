package de.littletrotter.ui.view

import com.vaadin.flow.data.provider.DataProvider

fun <E> createGridDataProvider(
    fetch: (pageIndex: Int, limit: Int, filter: String?) -> List<E>,
    count: (filter: String?) -> Long,
    filterSupplier: () -> String?,
): DataProvider<E, Void> {
    return DataProvider.fromCallbacks(
        { query ->
            val limit = query.limit
            val offset = query.offset
            val pageIndex = if (limit == 0) 0 else offset / limit
            val items = fetch(pageIndex, limit, filterSupplier())
            items.stream()
        },
        { _ -> count(filterSupplier()).toInt() },
    )
}

/** Keeps backend ordering ahead of paging for lazy grids. */
fun <E> createSortableGridDataProvider(
    fetch: (pageIndex: Int, limit: Int, filter: String?, sortField: String?, direction: de.littletrotter.domain.shared.SortDirection) -> List<E>,
    count: (filter: String?) -> Long,
    filterSupplier: () -> String?,
): DataProvider<E, Void> = DataProvider.fromCallbacks(
    { query ->
        val order = query.sortOrders.firstOrNull()
        val direction = if (order?.direction == com.vaadin.flow.data.provider.SortDirection.DESCENDING)
            de.littletrotter.domain.shared.SortDirection.DESC else de.littletrotter.domain.shared.SortDirection.ASC
        fetch(if (query.limit == 0) 0 else query.offset / query.limit, query.limit, filterSupplier(), order?.sorted, direction).stream()
    },
    { count(filterSupplier()).toInt() },
)
