package de.littletrotter.application.quote.port.inbound

import de.littletrotter.application.command.CommandEntryPoint

/** Starts a new draft offer for a customer. */
interface CreateQuoteUseCase : CommandEntryPoint {

    /** Creates a draft for [customerId] and returns the new offer's id. */
    fun create(customerId: String): String
}
