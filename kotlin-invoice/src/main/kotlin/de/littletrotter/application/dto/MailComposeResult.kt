package de.littletrotter.application.dto

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
 * Outcome of a compose request. Every unmet precondition (including a document that is not
 * generated yet) is reported by [MailDispatchException], so the only answer is a ready draft.
 */
sealed interface MailComposeResult {
    data class Ready(val handover: MailComposeHandover) : MailComposeResult
}
