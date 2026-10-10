package de.littletrotter.application.quote.port.inbound

import de.littletrotter.application.command.CommandEntryPoint

/** Records the customer rejection of a sent offer. */
interface RejectQuoteUseCase : CommandEntryPoint {

    /** The customer rejected [quoteId] for [reason]. */
    fun reject(quoteId: String, reason: String)
}
