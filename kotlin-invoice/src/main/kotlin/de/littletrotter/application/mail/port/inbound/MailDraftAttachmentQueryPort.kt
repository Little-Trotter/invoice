package de.littletrotter.application.mail.port.inbound

/** One attachment of a compose draft exactly as the mail component will send it. */
data class MailDraftAttachmentContent(val filename: String, val contentType: String, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is MailDraftAttachmentContent && filename == other.filename && contentType == other.contentType &&
                bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * (31 * filename.hashCode() + contentType.hashCode()) + bytes.contentHashCode()
}

/**
 * Fetches one attachment of a live compose draft from the mail component, which alone holds the
 * staged bytes; `null` when the draft or the attachment is no longer there.
 */
fun interface MailDraftAttachmentQueryPort {
    fun findAttachment(draftId: String, index: Int): MailDraftAttachmentContent?
}
