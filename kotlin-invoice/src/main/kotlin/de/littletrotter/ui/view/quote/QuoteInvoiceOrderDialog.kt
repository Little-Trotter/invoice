package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.quote.port.inbound.QuoteInvoiceOrder
import de.littletrotter.application.quote.port.inbound.QuoteInvoiceOrderRequest
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.AppDialog
import de.littletrotter.ui.i18n.TranslationProvider
import java.time.LocalDate

private const val ORDER_REFERENCE_MAX_LENGTH = 100

/** Message key of the first rule the service period breaks, or `null` when it is consistent. */
internal fun invoiceOrderDatesError(from: LocalDate?, to: LocalDate?): String? =
    when {
        (from == null) != (to == null) -> "quote.invoiceOrder.dialog.error.periodBothEnds"
        from != null && to != null && to.isBefore(from) -> "quote.invoiceOrder.dialog.error.periodOrder"
        else -> null
    }

/**
 * Orders the invoice draft of a sent or accepted offer. The acceptance day is asked only for a
 * sent offer, because the order records the acceptance with it. Only the service period is asked:
 * its last day becomes the delivery date, because the invoice needs one day the service was
 * completed on. Left empty, the invoice date stands in for the delivery date.
 */
internal class QuoteInvoiceOrderDialog(
    private val host: TranslationProvider,
    time: TimeSource,
    needsAcceptance: Boolean,
    orderReferenceDefault: String?,
    previous: QuoteInvoiceOrder?,
    private val onOrder: (QuoteInvoiceOrderRequest) -> Boolean,
) : AppDialog(host.tr("quote.invoiceOrder.dialog.title"), Size.M) {

    internal val acceptedOn = DatePicker(host.tr("quote.accept.dialog.acceptedOn"))
    internal val orderReference = TextField(host.tr("quote.accept.dialog.orderReference"))
    internal val periodFrom = DatePicker(host.tr("quote.invoiceOrder.dialog.periodFrom"))
    internal val periodTo = DatePicker(host.tr("quote.invoiceOrder.dialog.periodTo"))
    internal val hint = Div(host.tr("quote.invoiceOrder.dialog.hint"))
    internal val error = Div()
    internal val confirmButton = AppButtons.primary(host.tr("quote.invoiceOrder.dialog.confirm")) { submit() }

    init {
        acceptedOn.value = time.today()
        acceptedOn.isVisible = needsAcceptance
        orderReference.maxLength = ORDER_REFERENCE_MAX_LENGTH
        orderReference.value = orderReferenceDefault.orEmpty()
        periodFrom.value = previous?.servicePeriodFrom
        periodTo.value = previous?.servicePeriodTo
        error.isVisible = false
        error.addClassName("quote-dialog-error")
        hint.addClassName("quote-dialog-hint")
        val period = HorizontalLayout(periodFrom, periodTo).apply { isPadding = false }
        listOf(acceptedOn, orderReference, periodFrom, periodTo).forEach { it.setWidthFull() }
        add(acceptedOn, orderReference, period, error, hint)
        footer(AppButtons.tertiary(host.tr("app.cancel")) { close() }, confirmButton)
    }

    private fun submit() {
        val day = if (acceptedOn.isVisible) acceptedOn.value ?: return else null
        val problem = invoiceOrderDatesError(periodFrom.value, periodTo.value)
        error.isVisible = problem != null
        error.text = problem?.let { host.tr(it) }.orEmpty()
        if (problem != null) return
        val order = QuoteInvoiceOrderRequest(
            acceptedOn = day,
            customerOrderReference = orderReference.value.trim().ifEmpty { null },
            deliveryDate = periodTo.value,
            servicePeriodFrom = periodFrom.value,
            servicePeriodTo = periodTo.value,
        )
        if (onOrder(order)) close()
    }
}
