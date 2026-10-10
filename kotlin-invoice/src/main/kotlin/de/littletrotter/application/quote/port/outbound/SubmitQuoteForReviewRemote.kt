package de.littletrotter.application.quote.port.outbound

/** Remote side of `SubmitQuoteForReviewUseCase`: the offer component call behind the application service. */
fun interface SubmitQuoteForReviewRemote {

    /** Submits [quoteId] and returns the number it received. */
    fun submit(quoteId: String): String
}
