package de.littletrotter.shared.mail

import java.time.Instant

/**
 * Historical version of a mail template — subject, body, and metadata.
 * Returned by the template version history endpoint.
 */
data class MailTemplateVersionView(
    val kind: MailDocumentKind,
    val language: MailLanguage,
    val version: Int,
    val subject: String,
    val body: String,
    val bccAddress: String?,
    val seededDefault: Boolean,
    val createdAt: Instant,
    val current: Boolean,
)
