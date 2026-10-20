package de.littletrotter.shared.mail

/** Outcome of a manual archive hand-over retry: the status the journal row has after the attempt, and the failure text if it failed again. */
data class MailArchiveHandoverRetryResult(
    val journalId: Long,
    val archiveHandoverStatus: MailArchiveHandoverStatus,
    val archiveHandoverError: String?,
)
