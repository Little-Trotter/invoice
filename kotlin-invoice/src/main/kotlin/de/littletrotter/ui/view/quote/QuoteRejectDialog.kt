package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.data.value.ValueChangeMode
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.AppDialog
import de.littletrotter.ui.i18n.TranslationProvider

private const val REASON_MAX_LENGTH = 1000

/** The customer's rejection of a sent offer; the reason is required so the log says why it was lost. */
internal class QuoteRejectDialog(
    host: TranslationProvider,
    private val onReject: (String) -> Boolean,
) : AppDialog(host.tr("quote.reject.dialog.title"), Size.S) {

    internal val reason = TextArea(host.tr("quote.reject.dialog.reason"))
    internal val confirmButton = AppButtons.primary(host.tr("quote.reject.dialog.confirm")) { submit() }

    init {
        reason.maxLength = REASON_MAX_LENGTH
        reason.valueChangeMode = ValueChangeMode.EAGER
        reason.setWidthFull()
        reason.addValueChangeListener { confirmButton.isEnabled = it.value.isNotBlank() }
        confirmButton.isEnabled = false
        add(reason)
        footer(AppButtons.tertiary(host.tr("app.cancel")) { close() }, confirmButton)
    }

    private fun submit() {
        if (onReject(reason.value.trim())) close()
    }
}
