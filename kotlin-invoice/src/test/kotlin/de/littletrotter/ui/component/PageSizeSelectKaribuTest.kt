package de.littletrotter.ui.component

import com.github.mvysny.kaributesting.v10.MockVaadin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PageSizeSelectKaribuTest {

    private val chosen = mutableListOf<Int>()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    @Test
    fun `offers 10, 20 and 50 and starts on the initial value without a callback`() {
        val select = PageSizeSelect("Per page", 50) { chosen += it }

        assertEquals(listOf(10, 20, 50), select.listDataView.items.toList())
        assertEquals(50, select.value)
        assertEquals("Per page", select.element.getAttribute("aria-label"))
        assertEquals(emptyList<Int>(), chosen)
    }

    @Test
    fun `defaults to 20 and reports each new choice but never an empty one`() {
        val select = PageSizeSelect("Per page") { chosen += it }
        assertEquals(20, select.value)

        select.value = 10
        select.value = null
        select.value = 50

        assertEquals(listOf(10, 50), chosen)
    }
}
