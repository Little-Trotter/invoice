package de.littletrotter.shared.mail

/**
 * Number of journal rows matching a [MailJournalQuery]'s filter, ignoring its sort and paging.
 * Returned by the journal count endpoint.
 */
data class MailJournalCount(
    val count: Long,
)
