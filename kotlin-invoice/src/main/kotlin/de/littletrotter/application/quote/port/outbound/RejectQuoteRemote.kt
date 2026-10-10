package de.littletrotter.application.quote.port.outbound

/** Remote side of `RejectQuoteUseCase`: the offer component call behind the application service. */
fun interface RejectQuoteRemote {

    /** The customer rejected [quoteId] for [reason]. */
    fun reject(quoteId: String, reason: String)
}
