package de.littletrotter.application.mail.port.outbound

/**
 * The person on whose behalf a call into the mail component is made.
 *
 * The component performs no authentication of its own: it trusts the identity this application
 * forwards (after the bearer token proved the call comes from here) and authorises by [groups].
 * [userName] is a display name, never an address, so the one thing that needs a real address —
 * the "test to me" mail — carries [email] separately; it is `null` when the identity provider
 * released no address, in which case that action must refuse rather than guess a recipient.
 */
data class MailCaller(
    val userName: String,
    val groups: List<String>,
    val email: String?,
)

/** Who is acting right now; resolved per call because the adapter is application-scoped. */
fun interface MailCallerProvider {

    /** The acting user, or the system identity when there is no request context or authentication is off. */
    fun currentCaller(): MailCaller
}
