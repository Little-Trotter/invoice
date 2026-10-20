package de.littletrotter.shared.mail

/**
 * Request to send a BCC test mail to verify the BCC address — optional override.
 * Accepted by the template BCC test endpoint.
 */
data class MailTemplateBccTestRequest(
    val address: String = "",
)
