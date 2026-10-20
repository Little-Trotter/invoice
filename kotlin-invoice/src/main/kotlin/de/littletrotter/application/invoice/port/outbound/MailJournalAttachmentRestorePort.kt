package de.littletrotter.application.invoice.port.outbound

/**
 * Asks the mail component for the attachment it actually sent for an invoice, identified by the
 * invoice number and the SHA-256 of the lost original. Answers null when the component is not
 * deployed, not reachable, or holds no byte-identical copy — every one of these means "not
 * restorable from mail", never an error.
 */
fun interface MailJournalAttachmentRestorePort {
    fun findAttachment(referenceKey: String, sha256: String): MailJournalAttachment?
}

/** A byte-identical copy of a sent attachment together with where the mail component found it. */
class MailJournalAttachment(val bytes: ByteArray, val journalId: Long, val file: String)
