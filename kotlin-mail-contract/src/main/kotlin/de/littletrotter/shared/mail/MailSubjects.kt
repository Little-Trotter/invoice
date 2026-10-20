package de.littletrotter.shared.mail

/**
 * The NATS subject names of the mail-dispatch context.
 *
 * Direction: [SEND_RESULT] travels component → host; [SEND_REQUESTED] never leaves the component.
 * Owner: the mail-dispatch context — this is the single place the names are *written down*, not
 * the place they are *configured*. Both deployables still read them from configuration
 * (`nats.subject.mail-send-*`, overridable via `MAIL_NATS_SUBJECT_SEND_REQUESTED` /
 * `MAIL_NATS_SUBJECT_SEND_RESULT`); these constants are the defaults those properties fall back
 * to, so a subject string is no longer typed twice — once in the component's
 * `application.properties` and once in a host consumer that has to match it.
 */
object MailSubjects {

    /**
     * The send envelope the component publishes to itself: `POST /api/send` writes the journal row
     * and hands the envelope to JetStream, the consumer performs SMTP.
     *
     * **Private to the component.** The host must never publish onto this subject — dispatch is an
     * internal step of the mail context, and the host's handover is the REST compose request
     * ([MailComposeRequest]). It is named here only so the host's stream/subject inventory and its
     * operational dashboards can refer to it without copying the literal.
     */
    const val SEND_REQUESTED: String = "mail.send.requested"

    /**
     * The terminal outcome of one send attempt ([MailSendResultEvent]), published by the component
     * after every SENT/FAILED transition and consumed by the host to flip the invoice's "versendet"
     * flag. Fire-and-forget: the component never calls back into the invoicing application.
     */
    const val SEND_RESULT: String = "mail.send.result"

    /**
     * Administrator notice about the archive hand-over ([MailArchiveHandoverNoticeEvent]): the archive
     * address is missing, or a hand-over failed. Carried by [MailStreams.RESULT].
     */
    const val ARCHIVE_HANDOVER_NOTICE: String = "mail.archive-handover.notice"
}
