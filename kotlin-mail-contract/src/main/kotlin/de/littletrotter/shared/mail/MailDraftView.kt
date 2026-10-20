package de.littletrotter.shared.mail

import java.time.Instant

/**
 * File metadata for a draft attachment — filename, size, integrity checksum and staged object key.
 * Nested in [MailDraftView].
 */
data class MailDraftAttachmentView(
    val filename: String,
    val contentType: String,
    val sizeBytes: Long,
    val sha256: String,
    /** Object key the staged copy lives under; the send request references the attachment by it. */
    val s3Key: String,
)

/**
 * Full state of a mail draft in compose — template, placeholders, attachments, and expiry.
 * Returned by draft view and compose endpoints.
 */
data class MailDraftView(
    val draftId: String,
    val referenceKey: String,
    val documentFamily: MailDocumentFamily,
    val documentKind: MailDocumentKind,
    val recipientAddress: String,
    val recipientName: String,
    val placeholderValues: Map<String, String>,
    val attachments: List<MailDraftAttachmentView>,
    val totalAttachmentBytes: Long,
    val composeUrl: String,
    val createdAt: Instant,
    val expiresAt: Instant,
)
