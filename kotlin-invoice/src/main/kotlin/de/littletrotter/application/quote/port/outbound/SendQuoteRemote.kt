package de.littletrotter.application.quote.port.outbound

import de.littletrotter.application.quote.port.inbound.QuoteDispatchHandover

/** Remote side of `SendQuoteUseCase`: the offer component call behind the application service. */
fun interface SendQuoteRemote {

    /** Starts the dispatch of [quoteId]. */
    fun send(quoteId: String): QuoteDispatchHandover
}
