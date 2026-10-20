package de.littletrotter.ui.view

import com.vaadin.flow.data.provider.Query
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GridDataProviderEdgeTest {

    @Test
    fun `limit zero yields pageIndex zero`() {
        val items = (1..5).map { "x$it" }
        val receivedPageIndices = mutableListOf<Int>()

        val fetch: (Int, Int, String?) -> List<String> = { pageIndex, limit, filter ->
            receivedPageIndices += pageIndex
            items
        }
        val count: (String?) -> Long = { _ -> items.size.toLong() }

        val provider = createGridDataProvider(fetch, count) { null }

        val q = Query<String, Void>(0, 0, java.util.Collections.emptyList(), java.util.Comparator<String> { a, b -> a.compareTo(b) }, null)
        val result = provider.fetch(q).toList()
        assertEquals(items.size, result.size)
        assertEquals(1, receivedPageIndices.size)
        assertEquals(0, receivedPageIndices[0])
    }
}
