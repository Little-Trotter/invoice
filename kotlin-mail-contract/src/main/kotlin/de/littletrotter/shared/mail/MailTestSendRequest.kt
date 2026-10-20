package de.littletrotter.shared.mail

/**
 * Request to send a test mail to verify template rendering — optional recipient override.
 * Accepted by the test-send endpoint.
 */
data class MailTestSendRequest(
    val recipientAddress: String = "",
)
