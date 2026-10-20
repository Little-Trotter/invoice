package de.littletrotter.shared.mail

import java.time.Instant

/**
 * Outcome of a BCC test send — the BCC address, message ID, and send timestamp.
 * Returned by the template BCC test endpoint.
 */
data class MailTemplateBccTestResult(
    val bccAddress: String,
    val messageId: String,
    val sentAt: Instant,
)
