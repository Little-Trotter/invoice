package de.littletrotter.application.quote.port.inbound

import de.littletrotter.application.command.CommandEntryPoint

/** Hands a draft to review; the offer receives its number. */
interface SubmitQuoteForReviewUseCase : CommandEntryPoint {

    /** Submits [quoteId] and returns the number it received. */
    fun submit(quoteId: String): String
}
