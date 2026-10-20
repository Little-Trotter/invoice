package de.littletrotter.application.quote.port.inbound

import de.littletrotter.application.command.CommandEntryPoint

/** Removes a file from an offer. */
interface RemoveQuoteAttachmentUseCase : CommandEntryPoint {

    /** Removes [attachmentId] from [quoteId]. */
    fun remove(quoteId: String, attachmentId: String)
}
