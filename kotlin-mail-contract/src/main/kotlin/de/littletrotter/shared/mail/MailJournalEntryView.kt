package de.littletrotter.shared.mail

import java.time.Instant

/**
 * A dispatched mail in the journal — recipient, status, template used, and send metadata.
 * Returned by the journal query endpoint.
 */
data class MailJournalEntryView(
    val id: Long,
    val referenceKey: String,
    val recipient: String,
    val subject: String,
    val messageId: String?,
    val status: MailSendStatus,
    val smtpResult: String?,
    val templateKind: MailDocumentKind,
    val templateLanguage: MailLanguage,
    val templateVersion: Int,
    val bodyEditedByUser: Boolean,
    val retryOfJournalId: Long?,
    val createdAt: Instant,
    val attachments: List<MailJournalAttachmentView> = emptyList(),
    val archiveStatus: MailArchiveStatus = MailArchiveStatus.PENDING,
    val archiveS3Key: String? = null,
    /** Absent on the wire from older producers, which have no external archive handover. */
    val archiveHandoverStatus: MailArchiveHandoverStatus = MailArchiveHandoverStatus.NOT_CONFIGURED,
    val archiveHandoverAt: Instant? = null,
    val archiveHandoverError: String? = null,
)
