package de.littletrotter.application.mail.port.inbound

import de.littletrotter.application.command.CommandEntryPoint

/** Builds the archive package of an already sent mail again after the first attempt failed. */
interface RearchiveMailUseCase : CommandEntryPoint {

    /**
     * Re-runs archiving of the sent row [journalId] and answers with its outcome. Unlike a retry
     * nothing is queued: the result is known once this returns.
     */
    fun rearchive(journalId: Long): MailRearchiveOutcome
}

/** Archive state of a journal row after a rearchive attempt; [archiveError] explains a failure. */
data class MailRearchiveOutcome(
    val journalId: Long,
    val archiveStatus: MailJournalArchiveStatus,
    val archiveError: String?,
)
