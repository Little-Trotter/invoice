package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.QuoteCustomerLookupQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteCustomerOption
import de.littletrotter.application.quote.port.outbound.QuoteCustomerLookupRemote
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Application service of `QuoteCustomerLookupQueryPort`; the call itself is the outbound `QuoteCustomerLookupRemote`. */
@ApplicationScoped
class QuoteCustomerLookupQueryService @Inject constructor(
    private val remote: QuoteCustomerLookupRemote,
) : QuoteCustomerLookupQueryPort {

    override fun customers(text: String, limit: Int): List<QuoteCustomerOption> =
        remote.customers(text, limit)
}
