package de.littletrotter.ui.component

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.icon.VaadinIcon

/**
 * RDS-1 — shared boolean-flag icon badges for CRUD grids: `true` renders a colored [VaadinIcon]
 * with both a `title` tooltip and an `aria-label`, `false` renders an aria-hidden empty
 * placeholder (no dash — a boolean's absence is the signal, not a text glyph). Same visual
 * language as the sender-profile grid's default-marker star.
 *
 * Previously only reachable through inheriting from `AbstractCrudReferenceTab`; a plain function
 * lets any `AbstractCrudView` use it without that intermediate base class. The caller passes its
 * own translation function so this stays independent of any specific view hierarchy.
 */
fun createDefaultBadge(isDefault: Boolean, tr: (String) -> String): Component =
    booleanFlagIcon(isDefault, VaadinIcon.STAR, "lt-text-primary", tr("referenceData.defaultBadge"))

/** RDS-1 — same pattern as [createDefaultBadge] for the "domestic rate" flag. */
fun createInlandBadge(inland: Boolean, tr: (String) -> String): Component =
    booleanFlagIcon(inland, VaadinIcon.HOME, "lt-text-secondary", tr("referenceData.inlandBadge"))

private fun booleanFlagIcon(flag: Boolean, icon: VaadinIcon, colorClass: String, label: String): Component {
    if (!flag) {
        return Span().also { it.element.setAttribute("aria-hidden", "true") }
    }
    return Icon(icon).apply {
        addClassName(colorClass)
        element.setAttribute("title", label)
        element.setAttribute("aria-label", label)
    }
}
