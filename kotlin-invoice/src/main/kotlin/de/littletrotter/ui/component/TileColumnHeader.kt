package de.littletrotter.ui.component

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.icon.VaadinIcon

/**
 * PO follow-up (2026-09-17) — a narrow sortable Grid column header has no room for
 * its own written word next to the sort-order arrow. This button replaces that word with a
 * MEANINGFUL icon (never a generic "info" glyph, and never one of the icons the column's own cell
 * tiles already use) so the column still names itself at a glance; `title` keeps carrying the
 * column's real word, `aria-label` extends it with the action the click performs — a header must
 * never go visually or accessibly nameless just because it is narrow.
 *
 * The click only opens [onClick]'s legend; it never touches sort. Grid renders its own
 * sort-order arrow beside whatever header content a column is given, so this button — the header
 * CONTENT — and that arrow are always two separate elements: clicking one never fires the other's
 * listener.
 */
fun tileColumnHeader(icon: VaadinIcon, titleWord: String, ariaLabel: String, onClick: () -> Unit): Component =
    Button(icon.create()).apply {
        addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_ICON)
        addClassName("tile-column-header")
        element.setAttribute("title", titleWord)
        element.setAttribute("aria-label", ariaLabel)
        addClickListener { onClick() }
    }
