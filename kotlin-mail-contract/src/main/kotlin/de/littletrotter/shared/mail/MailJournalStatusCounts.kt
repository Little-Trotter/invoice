package de.littletrotter.shared.mail

/**
 * Summary counts of dispatches per status in the journal.
 * Returned by the journal status counts endpoint.
 */
data class MailJournalStatusCounts(
    val counts: Map<MailSendStatus, Long>,
)
