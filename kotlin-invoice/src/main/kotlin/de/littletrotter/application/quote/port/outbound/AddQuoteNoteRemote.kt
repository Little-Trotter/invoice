package de.littletrotter.application.quote.port.outbound

/** Remote side of `AddQuoteNoteUseCase`: the offer component call behind the application service. */
fun interface AddQuoteNoteRemote {

    /** Appends [text] to the log of [quoteId]. */
    fun addNote(quoteId: String, text: String)
}
