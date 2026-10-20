package de.littletrotter.shared.mail

import java.time.Instant

/**
 * Current state of a mail template — subject, body, BCC address, and creation metadata.
 * Returned by the template view endpoint.
 */
data class MailTemplateView(
    val id: Long,
    val kind: MailDocumentKind,
    val language: MailLanguage,
    val version: Int,
    val subject: String,
    val body: String,
    val bccAddress: String?,
    val seededDefault: Boolean,
    val createdAt: Instant,
)
