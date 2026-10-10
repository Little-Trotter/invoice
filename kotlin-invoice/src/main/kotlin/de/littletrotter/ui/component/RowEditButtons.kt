package de.littletrotter.ui.component

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import de.littletrotter.ui.i18n.UiTranslations

/**
 * The save/discard pair of an inline row editor. Both are small icon buttons because they sit inside
 * a grid row; the confirm one is filled so the commit stays the one obvious action of the row.
 */
object RowEditButtons {
    fun confirm(onClick: () -> Unit): Button =
        AppButtons.icon(ActionIcons.CONFIRM, UiTranslations.tr("app.save"), onClick).apply {
            removeThemeVariants(ButtonVariant.LUMO_TERTIARY)
            addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_PRIMARY)
        }

    fun cancel(onClick: () -> Unit): Button =
        AppButtons.icon(ActionIcons.CANCEL, UiTranslations.tr("app.cancel"), onClick).apply {
            addThemeVariants(ButtonVariant.LUMO_SMALL)
        }
}
