package de.littletrotter.ui.component

import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Div

/**
 * Box for a grid that shows all its rows but has more columns than a desktop content area can
 * hold (the line items with the side navigation open). The grid runs as wide as its columns need
 * and this box scrolls sideways instead of the grid itself.
 *
 * Why not let the grid scroll: its scroller sits in the grid's shadow DOM, where the theme cannot
 * style the scrollbar. With overlay scrollbars (the macOS default) that scrollbar stays invisible,
 * so the right-hand columns look cut off and a mouse user cannot reach them. This box is plain
 * light DOM, so the theme gives it a styled scrollbar that browsers always draw. The price: an
 * end-frozen action column scrolls with the rest, because the grid no longer scrolls on its own.
 */
class GridScrollBox(val grid: Grid<*>) : Div() {
    init {
        addClassName(CSS_CLASS)
        setWidthFull()
        // The box sizes the grid (stylesheet: column width, at least the box width); an inline
        // full width would win over that rule and pin the grid back to the box.
        grid.width = null
        add(grid)
    }

    companion object {
        const val CSS_CLASS = "lt-grid-scroll"
    }
}
