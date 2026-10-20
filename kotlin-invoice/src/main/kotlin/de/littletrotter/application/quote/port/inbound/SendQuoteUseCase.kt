package de.littletrotter.application.quote.port.inbound

import de.littletrotter.application.command.CommandEntryPoint

/** Hands a reviewed offer to the mail component, which owns the actual dispatch. */
interface SendQuoteUseCase : CommandEntryPoint {

    /** Starts the dispatch of [quoteId]; the answer says where the user continues. */
    fun send(quoteId: String): QuoteDispatchHandover
}
