package de.littletrotter.application.quote.port.inbound

import de.littletrotter.application.command.CommandEntryPoint

/** Asks for the rendering of one copy of an offer; the document appears as pending until the worker has answered. */
interface RequestQuoteDocumentUseCase : CommandEntryPoint {

    /** Requests the [mark] copy of [quoteId] and returns the id of the new document. */
    fun request(quoteId: String, mark: QuoteDocumentMark): String
}
