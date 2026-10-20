package de.littletrotter.ui.component

import com.vaadin.flow.component.grid.ColumnTextAlign
import com.vaadin.flow.component.grid.Grid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GridColumnsTest {

    private fun column(): Grid.Column<String> = Grid<String>().addColumn { it }.setHeader("Name")

    @Test
    fun `action column is frozen to the end, fixed and without header`() {
        val col = column().asActionColumn()

        assertEquals("", col.headerText ?: "")
        assertTrue(col.isAutoWidth)
        assertEquals(0, col.flexGrow)
        assertFalse(col.isResizable)
        assertFalse(col.isSortable)
        assertEquals(ColumnTextAlign.END, col.textAlign)
        assertTrue(col.isFrozenToEnd)
    }

    @Test
    fun `compact column sizes to content and does not grow`() {
        val col = column().asCompactColumn()

        assertTrue(col.isAutoWidth)
        assertEquals(0, col.flexGrow)
        assertFalse(col.isFrozenToEnd)
    }

    @Test
    fun `text column defaults to medium width`() {
        val col = column().asTextColumn()

        assertFalse(col.isAutoWidth)
        assertEquals(TextColumnWidth.MEDIUM.css, col.width)
        assertEquals(TextColumnWidth.MEDIUM.grow, col.flexGrow)
    }

    @Test
    fun `text column takes width and growth from every size`() {
        TextColumnWidth.entries.forEach { width ->
            val col = column().setAutoWidth(true).asTextColumn(width)

            assertFalse(col.isAutoWidth)
            assertEquals(width.css, col.width)
            assertEquals(width.grow, col.flexGrow)
        }
    }

    @Test
    fun `text column sizes are ordered and have the documented values`() {
        assertEquals("8rem", TextColumnWidth.SMALL.css)
        assertEquals("12rem", TextColumnWidth.MEDIUM.css)
        assertEquals("16rem", TextColumnWidth.LARGE.css)
        assertEquals(listOf(1, 2, 3), TextColumnWidth.entries.map { it.grow })
    }

    @Test
    fun `numeric column is right aligned, compact and carries the number part`() {
        val col = column().asNumericColumn()

        assertEquals(ColumnTextAlign.END, col.textAlign)
        assertTrue(col.isAutoWidth)
        assertEquals(0, col.flexGrow)
        assertNotNull(col.partNameGenerator)
    }
}
