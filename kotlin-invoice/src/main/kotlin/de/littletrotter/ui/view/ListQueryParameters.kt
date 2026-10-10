package de.littletrotter.ui.view

import com.vaadin.flow.router.QueryParameters

internal const val LIST_PARAM_SORT_FIELD = "sortField"
internal const val LIST_PARAM_SORT_DIR = "sortDir"
internal const val LIST_PARAM_PAGE = "page"
internal const val LIST_PARAM_SIZE = "size"

/** Where a paged, sorted list stands: the part of its URL state that is not a filter. */
internal data class ListPosition(val page: Int, val size: Int, val sortField: Enum<*>, val sortDir: Enum<*>)

/**
 * The URL form of a paged, sorted list: filter parameters first, then page, size and sort. Both
 * server-side lists mirror their state this way, so a deep link reads the same in either of them.
 */
internal fun listQueryParameters(writeFilters: ((String, String) -> Unit) -> Unit, position: ListPosition): QueryParameters {
    val params = linkedMapOf<String, List<String>>()
    writeFilters { key, value -> params[key] = listOf(value) }
    params[LIST_PARAM_PAGE] = listOf(position.page.toString())
    params[LIST_PARAM_SIZE] = listOf(position.size.toString())
    params[LIST_PARAM_SORT_FIELD] = listOf(position.sortField.name)
    params[LIST_PARAM_SORT_DIR] = listOf(position.sortDir.name)
    return QueryParameters(params)
}
