package de.littletrotter.application.mail

import java.time.Instant

/**
 * The invoicing application's own local model of one terminal send outcome,
 * translated from the contract's `de.littletrotter.shared.mail.MailSendResultEvent` at
 * `de.littletrotter.infrastructure.messaging.acl.MailSendResultTranslator`, the one place on the
 * inbound side that may still import `de.littletrotter.shared.mail..`.
 *
 * [MailSendResultProjection.project] consumes this instead of the contract event, so nothing in
 * `application.mail` needs to know the wire shape any more — see [MailDeliveryOutcome] for why the
 * outcome itself is a local enum too.
 */
data class MailDeliveryReport(
    /** The component's journal row id — opaque to the host, useful only as a link/support handle. */
    val journalId: Long,
    /** Echoed verbatim from the compose handover's reference key; the host's join key. */
    val referenceKey: String,
    /** SENT or FAILED — see [MailDeliveryOutcome]. */
    val outcome: MailDeliveryOutcome,
    /** The SMTP Message-ID on success; `null` on [MailDeliveryOutcome.FAILED]. */
    val messageId: String?,
    /** When the component recorded the terminal transition, UTC. */
    val sentAt: Instant,
)

/**
 * The terminal outcome of one send attempt, as the invoicing application understands it.
 *
 * Deliberately not the contract's own `MailSendOutcome`: neither side depends on the other's enum,
 * and this local enum is what [MailSendResultProjection] and its tests are written
 * against. The translator maps the wire enum onto this one, name for name, with an exhaustive
 * `when` — the same reasoning `MailDocumentFamily`'s own KDoc records for its enum.
 */
enum class MailDeliveryOutcome {
    /** The SMTP handover succeeded; [MailDeliveryReport.messageId] carries the Message-ID. */
    SENT,

    /** The attempt failed terminally and is never overwritten — a retry is a new journal row. */
    FAILED,
}
