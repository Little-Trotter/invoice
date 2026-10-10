package de.littletrotter.ui.component

/**
 * Base width and growth share of a text column. Text columns keep their base width so they are never
 * squeezed; on wide screens the remaining space is split between them according to [grow].
 */
enum class TextColumnWidth(val css: String, val grow: Int) {
    SMALL("8rem", 1),
    MEDIUM("12rem", 2),
    LARGE("16rem", 3),
}
