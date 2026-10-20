package de.littletrotter.shared.mail

/**
 * Outcome of a rearchive request — the journal entry ID, archive status, and optional error message.
 * Returned by the rearchive endpoint.
 */
data class MailRearchiveResult(
    val journalId: Long,
    val archiveStatus: MailArchiveStatus,
    val archiveError: String?,
)
