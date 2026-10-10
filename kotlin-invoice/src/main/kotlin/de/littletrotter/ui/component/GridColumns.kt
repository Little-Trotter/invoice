package de.littletrotter.ui.component

import com.vaadin.flow.component.grid.ColumnTextAlign
import com.vaadin.flow.component.grid.Grid

/**
 * Row action column (icon buttons, download anchors). Stays at the right edge and visible while the
 * rest of the grid scrolls horizontally on narrow screens.
 */
fun <T> Grid.Column<T>.asActionColumn(): Grid.Column<T> =
    setHeader("")
        .setAutoWidth(true)
        .setFlexGrow(0)
        .setResizable(false)
        .setSortable(false)
        .setTextAlign(ColumnTextAlign.END)
        .setFrozenToEnd(true)

/** Column sized to its content that never takes extra space: codes, dates, status chips, units. */
fun <T> Grid.Column<T>.asCompactColumn(): Grid.Column<T> =
    setAutoWidth(true).setFlexGrow(0)

/** Free-text column with a fixed base width that takes its share of the remaining width. */
fun <T> Grid.Column<T>.asTextColumn(width: TextColumnWidth = TextColumnWidth.MEDIUM): Grid.Column<T> =
    setAutoWidth(false).setWidth(width.css).setFlexGrow(width.grow)

/**
 * Right-aligned money or quantity column. The `bau-num` part gives tabular figures and a right
 * padding so amounts line up across rows; sized to content like a compact column.
 */
fun <T> Grid.Column<T>.asNumericColumn(): Grid.Column<T> =
    setTextAlign(ColumnTextAlign.END)
        .setPartNameGenerator { "bau-num" }
        .setAutoWidth(true)
        .setFlexGrow(0)
