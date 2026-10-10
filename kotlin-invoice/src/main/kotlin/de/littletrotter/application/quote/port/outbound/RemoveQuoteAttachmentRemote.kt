package de.littletrotter.application.quote.port.outbound

/** Remote side of `RemoveQuoteAttachmentUseCase`: the offer component call behind the application service. */
fun interface RemoveQuoteAttachmentRemote {

    /** Removes [attachmentId] from [quoteId]. */
    fun remove(quoteId: String, attachmentId: String)
}
