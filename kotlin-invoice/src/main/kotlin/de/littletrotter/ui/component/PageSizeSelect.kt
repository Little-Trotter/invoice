package de.littletrotter.ui.component

import com.vaadin.flow.component.combobox.ComboBox

/**
 * The "per page" choice every paged list offers: 10, 20 or 50 rows. [onSizeChange] runs for each
 * new selection, whether the user or the code made it; it never sees an empty selection.
 */
class PageSizeSelect(
    label: String,
    initial: Int = DEFAULT_SIZE,
    onSizeChange: (Int) -> Unit,
) : ComboBox<Int>(label) {

    init {
        setItems(SIZES)
        setItemLabelGenerator { it.toString() }
        element.setAttribute("aria-label", label)
        value = initial
        addValueChangeListener { event -> event.value?.let(onSizeChange) }
    }

    companion object {
        const val DEFAULT_SIZE = 20
    }
}

private const val SMALL_SIZE = 10
private const val LARGE_SIZE = 50
private val SIZES = listOf(SMALL_SIZE, PageSizeSelect.DEFAULT_SIZE, LARGE_SIZE)
