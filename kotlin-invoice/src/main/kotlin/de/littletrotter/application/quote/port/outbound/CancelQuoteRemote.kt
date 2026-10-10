package de.littletrotter.application.quote.port.outbound

/** Remote side of `CancelQuoteUseCase`: the offer component call behind the application service. */
fun interface CancelQuoteRemote {

    /** Cancels [quoteId] for [reason]. */
    fun cancel(quoteId: String, reason: String)
}
