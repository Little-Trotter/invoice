package de.littletrotter.ui.component

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout

/**
 * Material "contextual app bar": replaces a table's normal header the moment at least one
 * row is selected, showing the selection count, a clear (X) control and one bulk action button.
 * Reused wherever a multi-select [com.vaadin.flow.component.grid.Grid] offers a bulk action — the
 * receipts tab's two tables and [de.littletrotter.ui.view.invoice.ReceiptDialog]'s attached grid —
 * so the markup and behaviour of the pattern cannot drift between call sites.
 *
 * Deliberately dumb: it only renders the count/clear/action row and delegates both clicks to the
 * caller. Callers own the selection state itself (a `Grid.addSelectionListener` calling
 * [setCount]/[isVisible]) and the actual bulk operation.
 */
class SelectionActionBar(
    private val countText: (Int) -> String,
    actionText: String,
    actionIcon: VaadinIcon,
    clearAriaLabel: String,
    onClear: () -> Unit,
    onAction: () -> Unit,
) : HorizontalLayout() {

    private val countSpan = Span()
    private val actionButton = Button(actionText, actionIcon.create())

    init {
        addClassName("selection-action-bar")
        setWidthFull()
        isSpacing = true
        isPadding = false
        alignItems = FlexComponent.Alignment.CENTER

        val clearButton = Button(VaadinIcon.CLOSE_SMALL.create()).apply {
            addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ICON)
            element.setAttribute("aria-label", clearAriaLabel)
            element.setProperty("title", clearAriaLabel)
            addClickListener { onClear() }
        }
        actionButton.apply {
            addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR)
            addClickListener { onAction() }
        }
        countSpan.addClassName("selection-action-bar__count")

        add(clearButton, countSpan, actionButton)
        setFlexGrow(1.0, countSpan)
    }

    /** Updates the "N selected" text; also drives whether the bar is worth showing (see
     *  call sites, which pair this with `isVisible = count > 0`). */
    fun setCount(count: Int) {
        countSpan.text = countText(count)
    }
}

/**
 * The trailing UNLINK icon button every receipt row (tab and dialog alike) offers while the
 * invoice is writable: extracted from what used to be [de.littletrotter.ui.view.invoice.ReceiptDialog]'s
 * own inline construction so the tab's row action and the dialog's row action cannot drift into
 * two slightly different buttons. [onClick] carries the caller's own detach/reload/notify sequence
 * — this factory only owns the button's look (icon, tertiary/error theming, tooltip, accessible
 * name), never the click behaviour itself.
 */
fun detachRowButton(title: String, onClick: () -> Unit): Button =
    Button(VaadinIcon.UNLINK.create()).apply {
        addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR)
        element.setProperty("title", title)
        element.setAttribute("aria-label", title)
        addClickListener { onClick() }
    }
