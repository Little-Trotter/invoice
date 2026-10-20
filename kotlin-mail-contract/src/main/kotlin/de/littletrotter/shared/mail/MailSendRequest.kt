package de.littletrotter.shared.mail

/**
 * Request to send a draft as a mail — template, placeholders, recipient, and attachment references.
 * Accepted by the send endpoint.
 */
data class MailSendRequest(
    val kind: MailDocumentKind,
    val documentFamily: MailDocumentFamily,
    val recipientAddress: String,
    val referenceKey: String,
    val subject: String,
    val body: String,
    val placeholderValues: Map<String, String?>,
    val bodyEditedByUser: Boolean,
    val attachments: List<MailSendAttachmentRef>,
    val draftId: String? = null,
)
