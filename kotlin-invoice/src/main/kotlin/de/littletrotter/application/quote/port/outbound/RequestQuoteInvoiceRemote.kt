package de.littletrotter.application.quote.port.outbound

import de.littletrotter.application.quote.port.inbound.QuoteInvoiceOrderRequest

/** Remote side of `RequestQuoteInvoiceUseCase`: the offer component call behind the application service. */
fun interface RequestQuoteInvoiceRemote {

    /** Places an invoice order for [quoteId]; returns the order id. */
    fun request(quoteId: String, order: QuoteInvoiceOrderRequest): String
}
