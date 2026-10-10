package de.littletrotter.ui.component

import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Div

class GridWrapper<T : Any>(val grid: Grid<T>) : Div() {
    init {
        addClassName("sp-grid-wrapper")
        setSizeFull()
        grid.setSizeFull()
        add(grid)
    }

    fun withPager(pager: Pager): GridWrapper<T> {
        add(pager)
        return this
    }
}
