package de.littletrotter.application.mail.port.outbound

import de.littletrotter.application.mail.port.inbound.MailSendCommand
import de.littletrotter.application.mail.port.inbound.MailSendOutcome

/** What the application needs from the mail component to hand over a confirmed mail. */
fun interface MailDeliveryPort {

    /** Queues [command] for delivery; returns once the component recorded it. */
    fun send(command: MailSendCommand): MailSendOutcome
}
