package de.littletrotter.ui.component

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.shared.Tooltip
import de.littletrotter.ui.i18n.UiTranslations

/**
 * The only place buttons are built, so one role always looks the same on every page. Roles map to
 * Lumo variants: primary = the one main action, secondary = default, tertiary = low emphasis,
 * danger = destructive. Icon-only buttons must carry a tooltip, which also becomes the accessible
 * name, because a bare icon tells screen-reader and keyboard users nothing.
 */
object AppButtons {
    fun primary(text: String, icon: VaadinIcon? = null, onClick: () -> Unit): Button =
        build(text, icon, onClick).apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }

    fun secondary(text: String, icon: VaadinIcon? = null, onClick: () -> Unit): Button =
        build(text, icon, onClick)

    fun tertiary(text: String, icon: VaadinIcon? = null, onClick: () -> Unit): Button =
        build(text, icon, onClick).apply { addThemeVariants(ButtonVariant.LUMO_TERTIARY) }

    fun danger(text: String, icon: VaadinIcon? = null, onClick: () -> Unit): Button =
        build(text, icon, onClick).apply { addThemeVariants(ButtonVariant.LUMO_ERROR) }

    fun icon(icon: VaadinIcon, tooltip: String, onClick: () -> Unit): Button =
        Button(Icon(icon)) { onClick() }.apply {
            addThemeVariants(ButtonVariant.LUMO_ICON, ButtonVariant.LUMO_TERTIARY)
            Tooltip.forComponent(this).withText(tooltip)
            setAriaLabel(tooltip)
        }

    /** The one Excel download trigger: icon only, tooltip and accessible name from `app.export.spreadsheet`. */
    fun spreadsheetDownload(onClick: () -> Unit = {}): Button =
        icon(ActionIcons.SPREADSHEET, UiTranslations.tr("app.export.spreadsheet"), onClick)

    /** Text-only dismiss button; low emphasis so the filled main action stays the only focus. */
    fun cancel(onClick: () -> Unit): Button =
        tertiary(UiTranslations.tr("app.cancel"), null, onClick)

    private fun build(text: String, icon: VaadinIcon?, onClick: () -> Unit): Button =
        Button(text) { onClick() }.apply {
            if (icon != null) setIcon(Icon(icon))
        }
}
