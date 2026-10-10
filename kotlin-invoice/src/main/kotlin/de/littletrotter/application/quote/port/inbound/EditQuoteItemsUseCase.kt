package de.littletrotter.application.quote.port.inbound

import de.littletrotter.application.command.CommandEntryPoint

/** Changes the lines of a draft offer. */
interface EditQuoteItemsUseCase : CommandEntryPoint {

    /** Replaces all lines of the draft [quoteId] by [lines] in the given order. */
    fun editItems(quoteId: String, lines: List<QuoteItemInput>)
}
