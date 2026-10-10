package de.littletrotter.application.quote.port.inbound

import de.littletrotter.application.command.CommandEntryPoint

/** Attaches a file to an offer. */
interface AttachQuoteFileUseCase : CommandEntryPoint {

    /** Stores [upload] on [quoteId] and returns the attachment id. */
    fun attach(quoteId: String, upload: QuoteAttachmentUpload): String
}
