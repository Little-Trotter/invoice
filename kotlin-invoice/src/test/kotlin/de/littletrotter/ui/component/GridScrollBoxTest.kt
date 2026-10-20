package de.littletrotter.ui.component

import com.vaadin.flow.component.grid.Grid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GridScrollBoxTest {

    @Test
    fun `box carries the scroll class, fills the width and holds the grid as its only child`() {
        val grid = Grid<String>()

        val box = GridScrollBox(grid)

        assertTrue(box.hasClassName(GridScrollBox.CSS_CLASS))
        assertEquals("100%", box.width)
        assertSame(grid, box.grid)
        assertEquals(listOf(grid), box.children.toList())
    }

    @Test
    fun `an inline full width on the grid is cleared so the stylesheet can size it to its columns`() {
        val grid = Grid<String>().apply { setWidthFull() }

        GridScrollBox(grid)

        assertNull(grid.width)
    }

    @Test
    fun `a grid without an inline width stays without one`() {
        val grid = Grid<String>()

        GridScrollBox(grid)

        assertNull(grid.width)
    }
}
