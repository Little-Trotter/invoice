package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.QuoteHeaderField
import de.littletrotter.application.quote.port.inbound.QuoteHeaderFieldQueryPort
import de.littletrotter.application.quote.port.outbound.QuoteHeaderFieldRemote
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Application service of `QuoteHeaderFieldQueryPort`; the call itself is the outbound `QuoteHeaderFieldRemote`. */
@ApplicationScoped
class QuoteHeaderFieldQueryService @Inject constructor(
    private val remote: QuoteHeaderFieldRemote,
) : QuoteHeaderFieldQueryPort {

    override fun headerFields(quoteId: String): List<QuoteHeaderField> =
        remote.headerFields(quoteId)
}
