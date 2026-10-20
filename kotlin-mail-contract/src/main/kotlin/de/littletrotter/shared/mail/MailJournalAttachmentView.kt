package de.littletrotter.shared.mail

/**
 * File metadata for a journal entry attachment — filename, optional size, and integrity checksum.
 * `sizeBytes` is `null` when the journal row did not record a size.
 * Nested in [MailJournalEntryView].
 */
data class MailJournalAttachmentView(
    val filename: String,
    val contentType: String,
    val sizeBytes: Long? = null,
    val sha256: String,
)
