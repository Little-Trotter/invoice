package de.littletrotter.ui.component

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.dialog.Dialog
import de.littletrotter.ui.i18n.UiTranslations

/**
 * Dialog with fixed width steps, so dialogs of the same weight look alike. The footer order is
 * always cancel first, then the actions with the main action last, matching what users know.
 */
open class AppDialog(title: String, size: Size = Size.M) : Dialog() {

    enum class Size(val width: String) {
        S("min(92vw, 28rem)"),
        M("min(92vw, 40rem)"),
        L("min(94vw, 64rem)"),
    }

    init {
        headerTitle = title
        width = size.width
    }

    /** Replaces the footer: [cancel] first, then [actions]; the last action becomes the primary one. */
    fun footer(cancel: Button, vararg actions: Button) {
        footer.removeAll()
        cancel.addThemeVariants(ButtonVariant.LUMO_TERTIARY)
        (listOf(cancel) + actions).forEach { it.removeThemeVariants(ButtonVariant.LUMO_SMALL) }
        actions.lastOrNull()?.addThemeVariants(ButtonVariant.LUMO_PRIMARY)
        footer.add(cancel, *actions)
    }
}

/** Asks before deleting; the confirm button is red and the dialog can be cancelled. */
fun confirmDelete(title: String, text: String, onConfirm: () -> Unit): ConfirmDialog =
    ConfirmDialog().apply {
        setHeader(title)
        setText(text)
        setCancelable(true)
        setConfirmText(UiTranslations.tr("app.delete"))
        setCancelText(UiTranslations.tr("app.cancel"))
        setConfirmButtonTheme(ButtonVariant.LUMO_ERROR.variantName)
        addConfirmListener { onConfirm() }
        open()
    }
