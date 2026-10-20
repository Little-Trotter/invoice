package de.littletrotter.application.quote.port.outbound

/** Remote side of `ReturnQuoteToDraftUseCase`: the offer component call behind the application service. */
fun interface ReturnQuoteToDraftRemote {

    /** Returns [quoteId] to draft; [note] tells the author what to change. */
    fun returnToDraft(quoteId: String, note: String)
}
