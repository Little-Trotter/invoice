package de.littletrotter.application.quote.port.inbound

/** Reads the files attached to an offer. */
interface QuoteAttachmentQueryPort {

    /** Attachment metadata of [quoteId]. */
    fun attachments(quoteId: String): List<QuoteAttachment>

    /** The bytes of [attachmentId]; throws `QuoteNotFoundException` when it does not exist. */
    fun content(quoteId: String, attachmentId: String): QuoteAttachmentContent
}
