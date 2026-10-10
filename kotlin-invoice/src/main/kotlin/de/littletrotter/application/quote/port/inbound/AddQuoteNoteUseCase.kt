package de.littletrotter.application.quote.port.inbound

import de.littletrotter.application.command.CommandEntryPoint

/** Adds a free-text note to the history of an offer. */
interface AddQuoteNoteUseCase : CommandEntryPoint {

    /** Appends [text] to the log of [quoteId]. */
    fun addNote(quoteId: String, text: String)
}
