package de.littletrotter.shared.mail

/**
 * Outcome of a send request — the journal entry ID and send status.
 * Returned by the send endpoint.
 */
data class MailSendResult(
    val journalId: Long,
    val status: MailSendStatus,
)
