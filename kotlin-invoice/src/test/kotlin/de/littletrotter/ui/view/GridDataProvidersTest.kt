package de.littletrotter.ui.view

import com.vaadin.flow.data.provider.QuerySortOrder
import com.vaadin.flow.data.provider.Query
import com.vaadin.flow.data.provider.SortDirection
import de.littletrotter.domain.shared.SortDirection as DomainSortDirection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GridDataProvidersTest {

    @Test
    fun `pagination and filter supplier are applied`() {
        val items = (1..50).map { "item$it" }

        var receivedFilter: String? = null
        val fetch: (Int, Int, String?) -> List<String> = { pageIndex, limit, filter ->
            receivedFilter = filter
            val from = pageIndex * limit
            val to = kotlin.math.min(items.size, from + limit)
            if (from >= items.size) emptyList() else items.subList(from, to)
        }
        val count: (String?) -> Long = { _ -> items.size.toLong() }

        val provider = createGridDataProvider(fetch, count) { "search-term" }

        // first page
        val q1 = Query<String, Void>(0, 10, java.util.Collections.emptyList(), java.util.Comparator<String> { a, b -> a.compareTo(b) }, null)
        val page1 = provider.fetch(q1).toList()
        assertEquals(10, page1.size)
        assertEquals("item1", page1[0])
        assertEquals("search-term", receivedFilter)

        // second page (offset 10 -> pageIndex 1)
        val q2 = Query<String, Void>(10, 10, java.util.Collections.emptyList(), java.util.Comparator<String> { a, b -> a.compareTo(b) }, null)
        val page2 = provider.fetch(q2).toList()
        assertEquals(10, page2.size)
        assertEquals("item11", page2[0])
    }

    @Test
    fun `boundary - a query limit of zero falls back to page index zero, not a division by zero`() {
        var receivedPageIndex = -1
        val fetch: (Int, Int, String?) -> List<String> = { pageIndex, _, _ -> receivedPageIndex = pageIndex; emptyList() }
        val provider = createGridDataProvider(fetch, { 0L }) { null }

        provider.fetch(Query<String, Void>(0, 0, emptyList(), null, null)).toList()

        assertEquals(0, receivedPageIndex)
    }

    @Test
    fun `the count callback forwards the current filter and drives size()`() {
        var receivedFilter: String? = null
        val count: (String?) -> Long = { filter -> receivedFilter = filter; 42L }
        val provider = createGridDataProvider({ _, _, _ -> emptyList<String>() }, count) { "needle" }

        val size = provider.size(Query<String, Void>(0, 10, emptyList(), null, null))

        assertEquals(42, size)
        assertEquals("needle", receivedFilter)
    }

    // ── createSortableGridDataProvider ──────────────────────────────────────

    @Test
    fun `sortable provider defaults to ASC when no sort order is present`() {
        var receivedDirection: DomainSortDirection? = null
        val fetch: (Int, Int, String?, String?, DomainSortDirection) -> List<String> =
            { _, _, _, _, direction -> receivedDirection = direction; emptyList() }
        val provider = createSortableGridDataProvider(fetch, { 0L }) { null }

        provider.fetch(Query<String, Void>(0, 10, emptyList(), null, null)).toList()

        assertEquals(DomainSortDirection.ASC, receivedDirection)
    }

    @Test
    fun `sortable provider maps an ASCENDING sort order to ASC`() {
        var receivedDirection: DomainSortDirection? = null
        val fetch: (Int, Int, String?, String?, DomainSortDirection) -> List<String> =
            { _, _, _, _, direction -> receivedDirection = direction; emptyList() }
        val provider = createSortableGridDataProvider(fetch, { 0L }) { null }

        provider.fetch(Query<String, Void>(0, 10, listOf(QuerySortOrder("name", SortDirection.ASCENDING)), null, null)).toList()

        assertEquals(DomainSortDirection.ASC, receivedDirection)
    }

    @Test
    fun `sortable provider maps a DESCENDING sort order to DESC and forwards the sorted field`() {
        var receivedDirection: DomainSortDirection? = null
        var receivedSortField: String? = null
        val fetch: (Int, Int, String?, String?, DomainSortDirection) -> List<String> =
            { _, _, _, sortField, direction -> receivedSortField = sortField; receivedDirection = direction; emptyList() }
        val provider = createSortableGridDataProvider(fetch, { 0L }) { null }

        provider.fetch(Query<String, Void>(0, 10, listOf(QuerySortOrder("name", SortDirection.DESCENDING)), null, null)).toList()

        assertEquals(DomainSortDirection.DESC, receivedDirection)
        assertEquals("name", receivedSortField)
    }

    @Test
    fun `sortable provider's query limit of zero falls back to page index zero`() {
        var receivedPageIndex = -1
        val fetch: (Int, Int, String?, String?, DomainSortDirection) -> List<String> =
            { pageIndex, _, _, _, _ -> receivedPageIndex = pageIndex; emptyList() }
        val provider = createSortableGridDataProvider(fetch, { 0L }) { null }

        provider.fetch(Query<String, Void>(0, 0, emptyList(), null, null)).toList()

        assertEquals(0, receivedPageIndex)
    }

    @Test
    fun `sortable provider's count callback forwards the current filter`() {
        var receivedFilter: String? = null
        val count: (String?) -> Long = { filter -> receivedFilter = filter; 7L }
        val provider = createSortableGridDataProvider({ _, _, _, _, _ -> emptyList<String>() }, count) { "term" }

        val size = provider.size(Query<String, Void>(0, 10, emptyList(), null, null))

        assertEquals(7, size)
        assertEquals("term", receivedFilter)
    }
}
