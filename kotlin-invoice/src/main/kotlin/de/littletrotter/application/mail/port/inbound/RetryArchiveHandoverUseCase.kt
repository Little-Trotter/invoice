package de.littletrotter.application.mail.port.inbound

import de.littletrotter.application.command.CommandEntryPoint

/** Hands a sent mail to the archive address again after the first hand-over failed. */
interface RetryArchiveHandoverUseCase : CommandEntryPoint {

    /** Repeats the hand-over of journal row [journalId]; the customer is not mailed again. */
    fun retryArchiveHandover(journalId: Long): ArchiveHandoverRetryOutcome
}
