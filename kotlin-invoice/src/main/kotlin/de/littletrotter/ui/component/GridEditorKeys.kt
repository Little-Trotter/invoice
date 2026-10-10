package de.littletrotter.ui.component

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.grid.Grid

/**
 * Gives every editor field of a buffered row editor the keyboard contract users expect from a
 * spreadsheet: Enter commits the row, Escape throws the edit away. Shared so the invoice and the
 * offer item grids behave identically.
 */
internal fun Grid<*>.wireBufferedEditorKeys(fields: List<Component>) {
    fields.forEach { field ->
        field.element.addEventListener("keydown") { editor.save() }.apply {
            addEventData("event.key")
            filter = "event.key === 'Enter'"
        }
        field.element.addEventListener("keydown") { editor.cancel() }.apply {
            addEventData("event.key")
            filter = "event.key === 'Escape'"
        }
    }
}
