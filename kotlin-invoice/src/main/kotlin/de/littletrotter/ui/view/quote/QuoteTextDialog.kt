package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.data.value.ValueChangeMode
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.AppDialog
import de.littletrotter.ui.i18n.TranslationProvider

private const val TEXT_MAX_LENGTH = 1000

/**
 * A dialog that asks for one mandatory explanation (the note for a returned offer, the reason of a
 * cancellation or a rejection). Texts come from `<keyPrefix>.title|label|confirm`. The dialog
 * stays open while [onConfirm] reports that the component refused, so the entry is not lost.
 */
internal class QuoteTextDialog(
    host: TranslationProvider,
    keyPrefix: String,
    private val onConfirm: (String) -> Boolean,
) : AppDialog(host.tr("$keyPrefix.title"), Size.S) {

    internal val textArea = TextArea(host.tr("$keyPrefix.label"))
    internal val confirmButton = AppButtons.primary(host.tr("$keyPrefix.confirm")) { submit() }

    init {
        textArea.setWidthFull()
        textArea.maxLength = TEXT_MAX_LENGTH
        textArea.isRequiredIndicatorVisible = true
        textArea.valueChangeMode = ValueChangeMode.EAGER
        textArea.addValueChangeListener { confirmButton.isEnabled = it.value.isNotBlank() }
        confirmButton.isEnabled = false
        add(textArea)
        footer(AppButtons.tertiary(host.tr("app.cancel")) { close() }, confirmButton)
    }

    private fun submit() {
        if (onConfirm(textArea.value.trim())) close()
    }
}
