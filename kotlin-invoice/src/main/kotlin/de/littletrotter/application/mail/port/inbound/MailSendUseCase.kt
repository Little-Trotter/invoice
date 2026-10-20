package de.littletrotter.application.mail.port.inbound

import de.littletrotter.application.command.CommandEntryPoint

/** Hands a confirmed mail to the mail component for delivery. */
interface MailSendUseCase : CommandEntryPoint {

    /**
     * Queues [command] for delivery. Returns once the component has written the journal row, not
     * once the mail left: delivery and its result continue asynchronously.
     */
    fun send(command: MailSendCommand): MailSendOutcome
}
