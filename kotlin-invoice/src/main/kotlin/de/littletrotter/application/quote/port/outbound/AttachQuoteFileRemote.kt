package de.littletrotter.application.quote.port.outbound

import de.littletrotter.application.quote.port.inbound.QuoteAttachmentUpload
import de.littletrotter.application.quote.port.inbound.StoredQuoteFile

/** Remote side of `AttachQuoteFileUseCase`: the offer component call behind the application service. */
fun interface AttachQuoteFileRemote {

    /** Stores [upload] on [quoteId] and returns the attachment id with the outcome of a requested reduction. */
    fun attach(quoteId: String, upload: QuoteAttachmentUpload): StoredQuoteFile
}
