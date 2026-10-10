package de.littletrotter.infrastructure.messaging.acl

import de.littletrotter.application.mail.MailDeliveryOutcome
import de.littletrotter.application.mail.MailDeliveryReport
import de.littletrotter.shared.mail.MailSendOutcome
import de.littletrotter.shared.mail.MailSendResultEvent

/**
 * The anti-corruption layer between the invoicing application and the published
 * mail language (`de.littletrotter.shared.mail`), on the inbound (send-result) side.
 *
 * [de.littletrotter.infrastructure.messaging.MailSendResultConsumer] decodes the raw payload with
 * the contract's own codec ([MailSendResultEvent.decode]) and hands the result straight here; this
 * is the **only** class in `infrastructure.messaging` that turns a [MailSendResultEvent] into
 * [MailDeliveryReport], the local model
 * [de.littletrotter.application.mail.MailSendResultProjection] actually works with.
 */
object MailSendResultTranslator {

    /** Translates [event] into this application's own [MailDeliveryReport]. */
    fun toDeliveryReport(event: MailSendResultEvent): MailDeliveryReport =
        MailDeliveryReport(
            journalId = event.journalId,
            referenceKey = event.referenceKey,
            outcome = outcomeFor(event.status),
            messageId = event.messageId,
            sentAt = event.sentAt,
        )

    /**
     * The contract's [MailSendOutcome] mapped onto this application's [MailDeliveryOutcome].
     * Exhaustive `when`, not a name-based shortcut: a new wire constant fails the build here, not
     * silently at runtime (see [de.littletrotter.shared.mail.MailDocumentFamily]'s KDoc for the
     * same reasoning applied to its own enum pair).
     */
    internal fun outcomeFor(status: MailSendOutcome): MailDeliveryOutcome = when (status) {
        MailSendOutcome.SENT -> MailDeliveryOutcome.SENT
        MailSendOutcome.FAILED -> MailDeliveryOutcome.FAILED
    }
}
