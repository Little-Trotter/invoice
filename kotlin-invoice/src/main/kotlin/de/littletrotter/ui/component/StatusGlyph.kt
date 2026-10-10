package de.littletrotter.ui.component

import com.vaadin.flow.component.html.NativeButton
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.icon.VaadinIcon

/** Label, icon and tone of a [StatusGlyph], bundled so the clickable variant stays within its parameter budget. */
data class GlyphAppearance(val icon: VaadinIcon, val tone: ChipTone, val label: String, val pulsing: Boolean = false)

private fun Icon.markAsDecoration() {
    element.setAttribute("aria-hidden", "true")
}

/**
 * Symbol-only status for dense list overviews: the icon carries the state in its [tone] colour, the
 * full [label] is the tooltip and the accessible name. [pulsing] marks work in progress.
 */
class StatusGlyph(icon: VaadinIcon, tone: ChipTone, label: String, pulsing: Boolean = false) : Span() {
    init {
        addClassNames("status-glyph", "status-glyph--${tone.css}")
        if (pulsing) addClassName("status-glyph--pulse")
        element.setAttribute("role", "img")
        element.setAttribute("aria-label", label)
        element.setAttribute("title", label)
        add(icon.create().apply { markAsDecoration() })
    }
}

/**
 * The clickable variant of [StatusGlyph]: a real `<button>` (focusable, Enter/Space) whose accessible
 * name [ariaLabel] announces the action, while [tooltip] keeps the plain state explanation. The click
 * does not bubble to the grid row.
 */
fun clickableStatusGlyph(appearance: GlyphAppearance, ariaLabel: String, tooltip: String, onClick: () -> Unit): NativeButton =
    NativeButton().apply {
        addClassNames("status-glyph", "status-glyph--${appearance.tone.css}", "status-glyph--clickable")
        if (appearance.pulsing) addClassName("status-glyph--pulse")
        element.setAttribute("aria-label", ariaLabel)
        element.setAttribute("title", tooltip)
        add(appearance.icon.create().apply { markAsDecoration() })
        element.addEventListener("click") { onClick() }.stopPropagation()
    }
