package de.littletrotter.shared.mail

/**
 * Rendered output from a template — subject, body, language, and template version used.
 * Returned by the render preview endpoint.
 */
data class MailRenderPreview(
    val subject: String,
    val body: String,
    val templateLanguage: MailLanguage,
    val templateVersion: Int,
)
