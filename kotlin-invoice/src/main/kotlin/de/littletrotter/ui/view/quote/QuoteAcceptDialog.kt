package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.AppDialog
import de.littletrotter.ui.i18n.TranslationProvider
import java.time.LocalDate

private const val ORDER_REFERENCE_MAX_LENGTH = 100

/**
 * The customer's acceptance of a sent offer: the day it was accepted and, optionally, their own
 * order number. Accepting only records the fact; the invoice is ordered separately.
 */
internal class QuoteAcceptDialog(
    host: TranslationProvider,
    time: TimeSource,
    private val onAccept: (LocalDate, String?) -> Boolean,
) : AppDialog(host.tr("quote.accept.dialog.title"), Size.S) {

    internal val acceptedOn = DatePicker(host.tr("quote.accept.dialog.acceptedOn"))
    internal val orderReference = TextField(host.tr("quote.accept.dialog.orderReference"))
    internal val hint = Div(host.tr("quote.accept.dialog.hint"))
    internal val confirmButton = AppButtons.primary(host.tr("quote.accept.dialog.confirm")) { submit() }

    init {
        acceptedOn.value = time.today()
        orderReference.maxLength = ORDER_REFERENCE_MAX_LENGTH
        listOf(acceptedOn, orderReference).forEach { it.setWidthFull() }
        acceptedOn.addValueChangeListener { confirmButton.isEnabled = it.value != null }
        add(acceptedOn, orderReference, hint)
        footer(AppButtons.tertiary(host.tr("app.cancel")) { close() }, confirmButton)
    }

    private fun submit() {
        val day = acceptedOn.value ?: return
        if (onAccept(day, orderReference.value.trim().ifEmpty { null })) close()
    }
}
