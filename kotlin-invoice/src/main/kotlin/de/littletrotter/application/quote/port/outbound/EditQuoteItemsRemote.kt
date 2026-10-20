package de.littletrotter.application.quote.port.outbound

import de.littletrotter.application.quote.port.inbound.QuoteItemInput

/** Remote side of `EditQuoteItemsUseCase`: the offer component call behind the application service. */
fun interface EditQuoteItemsRemote {

    /** Replaces all lines of the draft [quoteId] by [lines] in the given order. */
    fun editItems(quoteId: String, lines: List<QuoteItemInput>)
}
