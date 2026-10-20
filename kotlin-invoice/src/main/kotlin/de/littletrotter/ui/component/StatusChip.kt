package de.littletrotter.ui.component

import com.vaadin.flow.component.HasStyle
import com.vaadin.flow.component.html.NativeButton
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.dom.Element

/**
 * The colour families of a status chip. Each tone maps to one `--lt-chip-<tone>-bg/-fg` pair in
 * `styles.css` (light and dark), so no caller ever names a colour. A status type picks its tone in
 * exactly one mapping function next to the enum's view code.
 */
enum class ChipTone(internal val css: String) {
    NEUTRAL("neutral"),
    ACCENT("accent"),
    OK("ok"),
    WARN("warn"),
    ERROR("error"),
    REVIEW("review"),
    TECH_ERROR("tech-error"),
    CANCELLATION("storno"),
}

/** Gives any styled component the capsule shape and colour pair of [tone]. */
fun HasStyle.applyChipTone(tone: ChipTone) {
    addClassNames("status-chip", "status-chip--${tone.css}")
}

/**
 * The shared status chip: a capsule with a leading dot whose label text and [tone] colour carry the
 * state. [pulsing] marks work in progress with a gentle dot animation. An [icon] replaces the dot
 * where the kind of state (e.g. a severity) should be recognisable by shape as well as colour.
 */
class StatusChip(text: String, tone: ChipTone, pulsing: Boolean = false, icon: VaadinIcon? = null) : Span(text) {
    init {
        applyChipTone(tone)
        if (pulsing) addClassName("status-chip--pulse")
        if (icon != null) {
            addClassName("status-chip--icon")
            addComponentAsFirst(icon.create().apply { element.setAttribute("aria-hidden", "true") })
        }
        element.setAttribute("role", "status")
    }
}

/** Label, [tone] and progress marker of a chip, bundled so [clickableStatusChip] stays within its parameter budget. */
data class ChipAppearance(val label: String, val tone: ChipTone, val pulsing: Boolean = false)

/**
 * The clickable variant of [StatusChip]: a real `<button>` (focusable, Enter/Space) whose accessible
 * name [ariaLabel] announces the action, while [tooltip] keeps the plain state explanation. The
 * click does not bubble to the grid row.
 */
fun clickableStatusChip(appearance: ChipAppearance, ariaLabel: String, tooltip: String, onClick: () -> Unit): NativeButton =
    NativeButton().apply {
        applyChipTone(appearance.tone)
        addClassName("status-chip--clickable")
        if (appearance.pulsing) addClassName("status-chip--pulse")
        element.setAttribute("aria-label", ariaLabel)
        element.setAttribute("title", tooltip)
        element.appendChild(Element.createText(appearance.label))
        element.addEventListener("click") { onClick() }.stopPropagation()
    }
