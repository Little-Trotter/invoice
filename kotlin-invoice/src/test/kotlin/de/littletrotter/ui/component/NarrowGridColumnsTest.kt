package de.littletrotter.ui.component

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NarrowGridColumnsTest {

    private val grid = DataTable(String::class.java)
    private val primary = grid.addColumn { it }
    private val secondary = grid.addColumn { it }
    private val switch = NarrowGridColumns(grid, listOf(secondary))

    @Test
    fun `below the breakpoint only the secondary columns are hidden`() {
        switch.apply(NARROW_GRID_MAX_WIDTH_PX - 1)

        assertTrue(switch.narrow)
        assertTrue(primary.isVisible)
        assertFalse(secondary.isVisible)
    }

    @Test
    fun `at and above the breakpoint every column is shown again`() {
        switch.apply(390)
        switch.apply(NARROW_GRID_MAX_WIDTH_PX)

        assertFalse(switch.narrow)
        assertTrue(secondary.isVisible)
    }

    @Test
    fun `the placeholder width reported while the browser boots changes nothing`() {
        switch.apply(390)
        switch.apply(-1)

        assertFalse(secondary.isVisible)
    }

    @Test
    fun `flexible columns shrink to a small base width on a phone and get their own sizing back`() {
        val text = grid.addColumn { it }.asTextColumn(TextColumnWidth.LARGE)
        val flexSwitch = NarrowGridColumns(grid, emptyList(), listOf(text))

        flexSwitch.apply(390)
        assertEquals("5rem", text.width)
        assertEquals(1, text.flexGrow)

        flexSwitch.apply(1200)
        assertEquals(TextColumnWidth.LARGE.css, text.width)
        assertEquals(TextColumnWidth.LARGE.grow, text.flexGrow)
    }
}
