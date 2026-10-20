package de.littletrotter.application.quote.port.outbound

/**
 * The person on whose behalf a call into the offer component is made.
 *
 * The component performs no authentication of its own: it trusts the identity this application
 * forwards (after the bearer token proved the call comes from here), records [userName] as the
 * actor of every change and authorises by [groups].
 */
data class QuoteCaller(val userName: String, val groups: List<String>)

/** Who is acting right now; resolved per call because the adapters are application-scoped. */
fun interface QuoteCallerProvider {

    /** The acting user, or the system identity when there is no request context or authentication is off. */
    fun currentCaller(): QuoteCaller
}
