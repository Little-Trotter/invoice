package de.littletrotter.application.quote.port.outbound

import de.littletrotter.application.quote.port.inbound.QuoteAttachment
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentContent

/** Remote side of `QuoteAttachmentQueryPort`: the offer component call behind the application service. */
interface QuoteAttachmentRemote {

    /** Attachment metadata of [quoteId]. */
    fun attachments(quoteId: String): List<QuoteAttachment>

    /** The bytes of [attachmentId]; throws `QuoteNotFoundException` when it does not exist. */
    fun content(quoteId: String, attachmentId: String): QuoteAttachmentContent
}
