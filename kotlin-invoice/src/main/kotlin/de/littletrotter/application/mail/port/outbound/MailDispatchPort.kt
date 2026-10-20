package de.littletrotter.application.mail.port.outbound

import de.littletrotter.application.mail.InvoiceMailDraft
import de.littletrotter.application.dto.MailComposeHandover
import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.mail.MailSendResultProjection

/**
 * The invoicing application's one outbound call into the mail-dispatch context.
 *
 * The host exposes **no** REST endpoint of its own and the component never calls back
 * . Everything that must survive
 * travels the other way as a NATS event
 * ([de.littletrotter.shared.mail.MailSendResultEvent], projected by
 * [MailSendResultProjection]); this port carries the single interactive step — a human is waiting
 * in front of a dialog for a draft id — and creates no durable state that matters: a lost draft
 * means the user presses the button again.
 *
 * **No implementation exists when the feature is off.** The HTTP adapter
 * (`de.littletrotter.infrastructure.mail.HttpMailDispatchAdapter`) is annotated
 * `@LookupIfProperty(name = "mail.enabled", stringValue = "true")`, so with `MAIL_ENABLED=false`
 * there is no bean at all — callers resolve this port through
 * [jakarta.enterprise.inject.Instance] and simply never render an entry point. That is the
 * concept's "no disabled buttons, no dead menu links" rule, enforced by the container rather than
 * by a check every call site could forget.
 */
fun interface MailDispatchPort {

    /**
     * Hands [draft] to the component's `POST /mail/api/compose` and returns where to open the
     * resulting draft. Throws [MailDispatchException] for every failure — unreachable component,
     * non-2xx answer, unreadable body: there is nothing useful to degrade to, since the user
     * pressed a button and is waiting for a dialog.
     *
     * [draft] is this application's own local model ([InvoiceMailDraft]), never the contract type —
     * the implementation (`HttpMailDispatchAdapter`) translates it at its own
     * adapter boundary, in `infrastructure.mail.acl`.
     */
    fun compose(draft: InvoiceMailDraft): MailComposeHandover
}
