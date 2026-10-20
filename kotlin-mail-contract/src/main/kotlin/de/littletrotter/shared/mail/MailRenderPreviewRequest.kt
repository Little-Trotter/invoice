package de.littletrotter.shared.mail

/**
 * Request to preview rendered output from a template — template kind and placeholder values.
 * Accepted by the render preview endpoint.
 */
data class MailRenderPreviewRequest(
    val kind: MailDocumentKind,
    val documentFamily: MailDocumentFamily,
    val placeholderValues: Map<String, String?>,
)
