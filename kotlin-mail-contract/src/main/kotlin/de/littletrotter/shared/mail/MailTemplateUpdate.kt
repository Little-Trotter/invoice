package de.littletrotter.shared.mail

/**
 * Request to update a mail template — subject, body, and optional BCC address.
 * Accepted by the template update endpoint.
 */
data class MailTemplateUpdate(
    val kind: MailDocumentKind,
    val language: MailLanguage,
    val subject: String,
    val body: String,
    val bccAddress: String? = null,
)
