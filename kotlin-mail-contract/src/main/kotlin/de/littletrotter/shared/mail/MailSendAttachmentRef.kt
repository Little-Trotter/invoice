package de.littletrotter.shared.mail

/**
 * Reference to an attachment copied into the mail service's S3 bucket.
 * Nested in [MailSendRequest].
 */
data class MailSendAttachmentRef(
    val s3Key: String,
    val filename: String,
    val contentType: String,
    val sha256: String,
)
