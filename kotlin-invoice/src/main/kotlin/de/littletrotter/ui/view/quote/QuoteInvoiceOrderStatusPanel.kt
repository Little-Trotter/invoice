package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import de.littletrotter.application.quote.port.inbound.QuoteDetail
import de.littletrotter.application.quote.port.inbound.QuoteInvoiceOrder
import de.littletrotter.application.quote.port.inbound.QuoteInvoiceOrderStatus
import de.littletrotter.shared.GermanTime
import de.littletrotter.ui.format.DateFormatter
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.i18n.TranslationProvider

/**
 * Where the latest invoice order of an offer stands: waiting since a time, answered with an
 * invoice that can be opened, or refused with the reason. Nothing is shown without an order. There
 * is no time-out state: the text names only how long the order has been waiting.
 */
internal class QuoteInvoiceOrderStatusPanel(private val host: TranslationProvider) : Div() {

    init {
        addClassName("quote-invoice-order")
        isVisible = false
    }

    fun render(detail: QuoteDetail) {
        removeAll()
        val order = detail.invoiceOrder
        isVisible = order != null
        if (order == null) return
        when (order.status) {
            QuoteInvoiceOrderStatus.REQUESTED -> add(requested(order))
            QuoteInvoiceOrderStatus.CREATED -> add(created(order))
            QuoteInvoiceOrderStatus.REJECTED -> add(rejected(order))
        }
    }

    private fun requested(order: QuoteInvoiceOrder): Span {
        val since = DateFormatter.format(order.requestedAt.atZone(GermanTime.ZONE).toLocalDateTime())
        return Span(host.tr("quote.invoiceOrder.requested", since)).apply { setId("quote-invoice-order-requested") }
    }

    private fun created(order: QuoteInvoiceOrder): Button {
        val id = order.invoiceId
        return AppButtons.tertiary(host.tr("quote.invoiceOrder.open", order.invoiceNumber.orEmpty())) {
            if (id != null) UI.getCurrent().navigate("invoice/$id")
        }.apply {
            setId("quote-invoice-order-open")
        }
    }

    private fun rejected(order: QuoteInvoiceOrder): Span =
        Span(host.tr("quote.invoiceOrder.rejected", order.rejectionReason.orEmpty())).apply { setId("quote-invoice-order-rejected") }
}
