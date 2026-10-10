package de.littletrotter.ui.component

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.data.provider.Query
import com.vaadin.flow.data.provider.QuerySortOrder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * UI-8a — [ComboBox.lazyItems] is the one implementation every "type to search a page at a time" combo in the programme shares, so it is the one place this exact behaviour needs proving: filter normalisation (null/blank/set), offset/limit pass-through, and an empty result. 100% line/branch coverage is required — small enough that there is no excuse for less.
 */
class LazyComboSupportTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try {
            MockVaadin.tearDown()
        } catch (_: Exception) {
        }
    }

    private fun query(offset: Int = 0, limit: Int = 60, filter: String? = null): Query<String, String> =
        Query(offset, limit, emptyList<QuerySortOrder>(), null, filter)

    // ── fetch — filter normalisation ─────────────────────────────────────────────

    @Test
    fun `fetch receives null when the ComboBox filter is null`() {
        var receivedFilter: String? = "unset"
        val combo = ComboBox<String>()
        val provider = combo.lazyItems(
            count = { 0 },
            fetch = { filter, _, _ -> receivedFilter = filter; emptyList() },
        )

        provider.fetch(query(filter = null)).toList()

        assertEquals(null, receivedFilter)
    }

    @Test
    fun `fetch normalises a blank filter to null`() {
        var receivedFilter: String? = "unset"
        val combo = ComboBox<String>()
        val provider = combo.lazyItems(
            count = { 0 },
            fetch = { filter, _, _ -> receivedFilter = filter; emptyList() },
        )

        provider.fetch(query(filter = "   ")).toList()

        assertEquals(null, receivedFilter)
    }

    @Test
    fun `fetch passes a non-blank filter through unchanged`() {
        var receivedFilter: String? = null
        val combo = ComboBox<String>()
        val provider = combo.lazyItems(
            count = { 0 },
            fetch = { filter, _, _ -> receivedFilter = filter; emptyList() },
        )

        provider.fetch(query(filter = "Meier")).toList()

        assertEquals("Meier", receivedFilter)
    }

    // ── fetch — offset/limit window, and the items the callback produces ────────

    @Test
    fun `fetch passes offset and limit through unchanged, beyond a 60-row page`() {
        var receivedOffset = -1
        var receivedLimit = -1
        val combo = ComboBox<String>()
        val provider = combo.lazyItems(
            count = { 0 },
            fetch = { _, offset, limit -> receivedOffset = offset; receivedLimit = limit; emptyList() },
        )

        // The exact shape of the bug this AP fixes: a window starting past the old 60-row cap.
        provider.fetch(query(offset = 61, limit = 30)).toList()

        assertEquals(61, receivedOffset)
        assertEquals(30, receivedLimit)
    }

    @Test
    fun `fetch returns an empty result unchanged`() {
        val combo = ComboBox<String>()
        val provider = combo.lazyItems(
            count = { 0 },
            fetch = { _, _, _ -> emptyList() },
        )

        val result = provider.fetch(query()).toList()

        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun `fetch returns the items the callback produces`() {
        val combo = ComboBox<String>()
        val provider = combo.lazyItems(
            count = { 0 },
            fetch = { _, _, _ -> listOf("Kunde 61", "Kunde 62") },
        )

        val result = provider.fetch(query()).toList()

        assertEquals(listOf("Kunde 61", "Kunde 62"), result)
    }

    // ── size — filter normalisation and the callback's count ────────────────────

    @Test
    fun `size receives null when the ComboBox filter is blank`() {
        var receivedFilter: String? = "unset"
        val combo = ComboBox<String>()
        val provider = combo.lazyItems(
            count = { filter -> receivedFilter = filter; 0 },
            fetch = { _, _, _ -> emptyList() },
        )

        provider.size(query(filter = ""))

        assertEquals(null, receivedFilter)
    }

    @Test
    fun `size passes a non-blank filter through and returns the callback's count`() {
        var receivedFilter: String? = null
        val combo = ComboBox<String>()
        val provider = combo.lazyItems(
            count = { filter -> receivedFilter = filter; 42 },
            fetch = { _, _, _ -> emptyList() },
        )

        val size = provider.size(query(filter = "Meier"))

        assertEquals("Meier", receivedFilter)
        assertEquals(42, size)
    }
}
