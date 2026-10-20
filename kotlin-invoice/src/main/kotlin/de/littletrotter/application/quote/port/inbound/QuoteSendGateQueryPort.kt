package de.littletrotter.application.quote.port.inbound

/** Whether an offer may be handed to the mail component now, and if not, the first condition that fails. */
fun interface QuoteSendGateQueryPort {

    /** The gate of offer [quoteId]; throws `QuoteNotFoundException` when it does not exist (any more). */
    fun gate(quoteId: String): QuoteSendGateState
}
