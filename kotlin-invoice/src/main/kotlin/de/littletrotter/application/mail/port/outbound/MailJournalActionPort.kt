package de.littletrotter.application.mail.port.outbound

import de.littletrotter.application.mail.port.inbound.MailRearchiveOutcome
import de.littletrotter.application.mail.port.inbound.MailSendOutcome

/** What the application needs from the mail component to act on an existing journal row. */
interface MailJournalActionPort {

    /** Queues a new send repeating the failed row [journalId]; returns the new linked row. */
    fun retry(journalId: Long): MailSendOutcome

    /** Re-runs archiving of the sent row [journalId] and returns its resulting archive state. */
    fun rearchive(journalId: Long): MailRearchiveOutcome
}
