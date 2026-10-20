package de.littletrotter.application.quote.port.outbound

import de.littletrotter.application.quote.port.inbound.QuoteSendGateState

/** Remote side of `QuoteSendGateQueryPort`: the offer component call behind the application service. */
fun interface QuoteSendGateRemote {

    /** The gate of offer [quoteId]; throws `QuoteNotFoundException` when it does not exist (any more). */
    fun gate(quoteId: String): QuoteSendGateState
}
