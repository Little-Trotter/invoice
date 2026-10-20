package de.littletrotter.ui.component

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.treegrid.TreeGrid
import com.vaadin.flow.data.renderer.Renderer
import com.vaadin.flow.function.ValueProvider

private const val TITLE = "title"

/**
 * Lightweight Grid subclass providing common helper columns (actions, money, contact) used across the app.
 *
 * Every column created through this component is resizable by mouse out of the box, so
 * individual views don't need to opt in. Columns can still opt out (`.setResizable(false)`) when
 * resizing genuinely makes no sense, e.g. pure icon/action columns.
 *
 * `multiselect` sets [Grid.SelectionMode.MULTI]; it is unrelated to Vaadin's `Grid(Class,
 * boolean)` `autoCreateColumns` parameter, which this class always disables — every call site
 * adds its own explicit columns, so reflection-based auto-columns would only ever push those out
 * of view.
 */
class DataTable<T : Any>(type: Class<T>, multiselect: Boolean = false) : Grid<T>(type, false) {

    init {
        if (multiselect) {
            selectionMode = SelectionMode.MULTI
        }
    }

    override fun addColumn(valueProvider: ValueProvider<T, *>): Column<T> =
        super.addColumn(valueProvider).also { it.isResizable = true }

    override fun addColumn(renderer: Renderer<T>): Column<T> =
        super.addColumn(renderer).also { it.isResizable = true }

    override fun <V : Component> addComponentColumn(componentProvider: ValueProvider<T, V>): Column<T> =
        super.addComponentColumn(componentProvider).also { it.isResizable = true }
}

/**
 * [TreeGrid] counterpart of [DataTable]: same "every column is resizable out of the
 * box" default, for the day-grouped "Druckaufträge" view
 * ([de.littletrotter.ui.view.invoice.PrintJobListView]), the first hierarchical grid this app has.
 */
class DataTreeTable<T : Any>(type: Class<T>) : TreeGrid<T>(type, false) {

    override fun addColumn(valueProvider: ValueProvider<T, *>): Column<T> =
        super.addColumn(valueProvider).also { it.isResizable = true }

    override fun addColumn(renderer: Renderer<T>): Column<T> =
        super.addColumn(renderer).also { it.isResizable = true }

    override fun <V : Component> addComponentColumn(componentProvider: ValueProvider<T, V>): Column<T> =
        super.addComponentColumn(componentProvider).also { it.isResizable = true }

    override fun <V : Component> addComponentHierarchyColumn(componentProvider: ValueProvider<T, V>): Column<T> =
        super.addComponentHierarchyColumn(componentProvider).also { it.isResizable = true }
}

/**
 * Shared helper for text columns whose content can visually truncate (long free text:
 * descriptions, notes, file names, message texts). Renders [valueProvider]'s full text in a
 * `Span` clipped to a single ellipsized line (`.lt-cell-ellipsis` in styles.css) and carries the
 * full text as the native-tooltip `title` attribute — the idiom already used by the B2B rules
 * "Beschreibung" column ([de.littletrotter.ui.view.referencedata.B2bProfilesTab]).
 *
 * NOT for numbers, dates, badges, status chips or action columns — those never need this.
 *
 * `Grid<T>` receiver (not [DataTable]'s own type) so [DataTreeTable] can use it too — both extend
 * `Grid` and neither overrides `addComponentColumn`'s signature in an incompatible way.
 */
fun <T : Any> Grid<T>.ellipsizedTextColumn(valueProvider: (T) -> String): Grid.Column<T> =
    addComponentColumn { item ->
        val text = valueProvider(item)
        Span(text).also {
            it.addClassName("lt-cell-ellipsis")
            if (text.isNotEmpty()) it.element.setAttribute(TITLE, text)
        }
    }

/**
 * Material two-line list item: a single column carrying an entity's identity (a short
 * primary line plus a longer, secondary line) instead of two separate columns. Use this when a
 * row's "name" and "description" belong together conceptually. Give the column flex-grow and no
 * auto-width, otherwise it sizes itself to the longest secondary text. NOT for two independent
 * free-text fields that each need their own sort/filter — those stay separate
 * [ellipsizedTextColumn]s.
 *
 * Renders `Div.lt-cell-two-line` containing `Span.lt-cell-two-line__primary` (single line,
 * ellipsized, full text as `title`) and, only when [secondary] returns a non-blank string,
 * `Span.lt-cell-two-line__secondary` (clamped to two lines via CSS, full text as `title`). A
 * blank/null secondary omits the second span entirely, so the row keeps single-line height
 * instead of reserving empty space.
 */
fun <T : Any> Grid<T>.twoLineTextColumn(primary: (T) -> String, secondary: (T) -> String?): Grid.Column<T> =
    addComponentColumn { item ->
        val primaryText = primary(item)
        val secondaryText = secondary(item)?.trim().orEmpty()
        Div().also { container ->
            container.addClassName("lt-cell-two-line")
            container.add(
                Span(primaryText).also {
                    it.addClassName("lt-cell-two-line__primary")
                    if (primaryText.isNotEmpty()) it.element.setAttribute(TITLE, primaryText)
                },
            )
            if (secondaryText.isNotEmpty()) {
                container.add(
                    Span(secondaryText).also {
                        it.addClassName("lt-cell-two-line__secondary")
                        it.element.setAttribute(TITLE, secondaryText)
                    },
                )
            }
        }
    }
