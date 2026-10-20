package de.littletrotter.application.quote.port.inbound

import de.littletrotter.application.command.CommandEntryPoint

/** Orders the invoice draft of a sent or accepted offer from the invoicing application. */
interface RequestQuoteInvoiceUseCase : CommandEntryPoint {

    /** Places an invoice order for [quoteId]; returns the id of the order the offer component recorded. */
    fun request(quoteId: String, order: QuoteInvoiceOrderRequest): String
}
