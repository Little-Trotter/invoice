package de.littletrotter.application.quote.port.outbound

import de.littletrotter.application.quote.port.inbound.QuoteDetail

/** Remote side of `QuoteDetailQueryPort`: the offer component call behind the application service. */
fun interface QuoteDetailRemote {

    /** The offer [quoteId]; throws `QuoteNotFoundException` when it does not exist (any more). */
    fun find(quoteId: String): QuoteDetail
}
