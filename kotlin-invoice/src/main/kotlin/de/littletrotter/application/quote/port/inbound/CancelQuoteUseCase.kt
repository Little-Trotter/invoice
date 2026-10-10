package de.littletrotter.application.quote.port.inbound

import de.littletrotter.application.command.CommandEntryPoint

/** Cancels an offer that is not final. */
interface CancelQuoteUseCase : CommandEntryPoint {

    /** Cancels [quoteId] for [reason]. */
    fun cancel(quoteId: String, reason: String)
}
