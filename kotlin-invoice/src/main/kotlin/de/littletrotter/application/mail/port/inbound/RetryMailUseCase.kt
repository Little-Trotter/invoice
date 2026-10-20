package de.littletrotter.application.mail.port.inbound

import de.littletrotter.application.command.CommandEntryPoint

/** Sends a failed journal row again as a new, linked row. */
interface RetryMailUseCase : CommandEntryPoint {

    /**
     * Queues a new send that repeats the failed row [journalId]; the original row stays as history.
     * Only a failed row can be repeated: any other state is refused by the component, so a double
     * click can never mail the customer twice.
     */
    fun retry(journalId: Long): MailSendOutcome
}
