package de.littletrotter.application.mail.port.outbound

import de.littletrotter.application.mail.port.inbound.ArchiveHandoverRetryOutcome
import de.littletrotter.application.mail.port.inbound.ArchiveHandoverSettings
import de.littletrotter.application.mail.port.inbound.ArchiveHandoverSettingsUpdate

/** What the application needs from the mail component for the archive hand-over. */
interface MailArchiveHandoverPort {

    fun settings(): ArchiveHandoverSettings

    fun save(update: ArchiveHandoverSettingsUpdate): ArchiveHandoverSettings

    /** Repeats the hand-over of journal row [journalId] and answers with its resulting state. */
    fun retry(journalId: Long): ArchiveHandoverRetryOutcome
}
