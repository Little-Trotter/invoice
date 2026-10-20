package de.littletrotter.shared.mail

/**
 * The JetStream stream names of the mail-dispatch context.
 *
 * Direction: neither — a stream name is shared vocabulary, read by the component that creates the
 * streams and by the host consumer that binds to [RESULT].
 * Owner: the mail-dispatch context. **Stream creation stays with the component**
 * (`MailNatsStreamSetup`): the host's own [de.littletrotter.shared.NatsStreamSetup] must not learn
 * these names, otherwise a mail-less instance would create two empty mail streams it never feeds
 * — nothing must be carried that an instance does not need. These constants are the
 * defaults behind the component's configuration, and the single place the literals are written.
 *
 * Two streams rather than one is a NATS server constraint, not a style choice: [MAIL] uses
 * `RetentionPolicy.WorkQueue` (each envelope is a job, consumed exactly once), which forces every
 * consumer to `DeliverPolicy.All` — wrong for a host-side result consumer or a test helper reading
 * "just the next result", so [RESULT] uses `RetentionPolicy.Limits` instead.
 */
object MailStreams {

    /** Carries [MailSubjects.SEND_REQUESTED]; WorkQueue retention, component-internal. */
    const val MAIL: String = "LITTLE_TROTTER_MAIL"

    /**
     * Carries [MailSubjects.SEND_RESULT]; Limits retention, so the host consumer survives a
     * restart and reading the stream does not consume it. The host binds a consumer here **only**
     * when `MAIL_ENABLED=true` — a consumer bound to a stream no component ever creates would
     * log-spam forever.
     */
    const val RESULT: String = "LITTLE_TROTTER_MAIL_RESULT"
}
