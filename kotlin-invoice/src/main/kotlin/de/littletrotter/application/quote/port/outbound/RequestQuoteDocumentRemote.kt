package de.littletrotter.application.quote.port.outbound

import de.littletrotter.application.quote.port.inbound.QuoteDocumentMark

/** Remote side of `RequestQuoteDocumentUseCase`: the offer component call behind the application service. */
fun interface RequestQuoteDocumentRemote {

    /** Requests the [mark] copy of [quoteId] and returns the id of the new document. */
    fun request(quoteId: String, mark: QuoteDocumentMark): String
}
