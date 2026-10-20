package de.littletrotter.shared.mail

import java.time.Instant

/**
 * Outcome of a test send — the recipient address, message ID, and send timestamp.
 * Returned by the test-send endpoint.
 */
data class MailTestSendResult(
    val recipientAddress: String,
    val messageId: String,
    val sentAt: Instant,
)
