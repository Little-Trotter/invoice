package de.littletrotter.ui.component

import com.vaadin.flow.component.AttachEvent
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.shared.Registration

/** Window width below which the secondary columns of a grid are hidden. */
const val NARROW_GRID_MAX_WIDTH_PX = 700

private const val NARROW_COLUMN_WIDTH = "5rem"

/**
 * Hides the [secondary] columns of [grid] on narrow windows so the columns that matter (status,
 * time, text) fit without horizontal scrolling. Columns are only toggled, never rebuilt, so sort
 * state and the loaded page survive a resize; the hidden facts remain reachable in the row details
 * or the detail views the grids already offer.
 */
class NarrowGridColumns(
    private val grid: Grid<*>,
    private val secondary: List<Grid.Column<*>>,
    private val flexible: List<Grid.Column<*>> = emptyList(),
) {

    private class Sizing(val autoWidth: Boolean, val width: String?, val flexGrow: Int)

    // Remembered once so the wide layout comes back exactly as the view configured it.
    private val original: Map<Grid.Column<*>, Sizing> =
        flexible.associateWith { Sizing(it.isAutoWidth, it.width, it.flexGrow) }

    private var resizeRegistration: Registration? = null

    /** Whether the secondary columns are currently hidden. */
    var narrow: Boolean = false
        private set

    /** Starts following the browser window while [grid] is attached. */
    fun install(): NarrowGridColumns {
        grid.addAttachListener { attach -> onAttach(attach) }
        grid.addDetachListener { onDetach() }
        return this
    }

    internal fun apply(widthPx: Int) {
        if (widthPx <= 0) return
        narrow = widthPx < NARROW_GRID_MAX_WIDTH_PX
        secondary.forEach { it.isVisible = !narrow }
        flexible.forEach { column ->
            val wide = original.getValue(column)
            if (narrow) {
                // Content-sized or fixed-width columns would sum above a phone's width; a small base width lets the text wrap instead.
                column.setAutoWidth(false).setWidth(NARROW_COLUMN_WIDTH).flexGrow = 1
            } else {
                column.setAutoWidth(wide.autoWidth).setWidth(wide.width).flexGrow = wide.flexGrow
            }
        }
    }

    private fun onAttach(attach: AttachEvent) {
        val page = attach.ui.page
        resizeRegistration = page.addBrowserWindowResizeListener { apply(it.width) }
        // The window size is -1 while the UI boots, so it is fetched once instead of read from the placeholder.
        val details = page.extendedClientDetails
        if (details.windowInnerWidth > 0) apply(details.windowInnerWidth) else details.refresh { apply(it.windowInnerWidth) }
    }

    private fun onDetach() {
        resizeRegistration?.remove()
        resizeRegistration = null
    }
}
