package de.littletrotter.application.mail

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

/**
 * What `POST /mail/api/compose` answers with: the component's own draft id and the URL the host
 * opens in an iframe dialog.
 *
 * [composeUrl] is deliberately taken verbatim from the component's answer instead of being
 * assembled here from a base URL plus [draftId]: the route under `/mail/…` belongs to the
 * component, and rebuilding it in the host would be a second, drift-prone copy of it. [draftId]
 * is kept only for logging and for the "open in a new tab" fallback message — the host never
 * interprets it.
 */
data class MailComposeHandover(
    val draftId: String,
    val composeUrl: String,
)

/**
 * Outcome of a compose request. [DocumentPending] is an expected state under load, not a failure:
 * the invoice's document generation has not delivered a completed, checksummed document yet.
 */
sealed interface MailComposeResult {
    data class Ready(val handover: MailComposeHandover) : MailComposeResult

    data object DocumentPending : MailComposeResult
}

/**
 * A compose handover that did not produce a draft. Carries a message the UI can show as-is;
 * never a silent degrade — see [de.littletrotter.shared.mail.MailContractViolationException]'s
 * own KDoc for why nothing on this path may default to an empty value.
 */
open class MailDispatchException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** The component answered 404: the addressed draft or resource does not exist (any more). */
class MailNotFoundException(message: String) : MailDispatchException(message)
