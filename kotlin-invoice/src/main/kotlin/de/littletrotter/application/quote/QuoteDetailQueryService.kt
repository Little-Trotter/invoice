package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.QuoteDetail
import de.littletrotter.application.quote.port.inbound.QuoteDetailQueryPort
import de.littletrotter.application.quote.port.outbound.QuoteDetailRemote
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Application service of `QuoteDetailQueryPort`; the call itself is the outbound `QuoteDetailRemote`. */
@ApplicationScoped
class QuoteDetailQueryService @Inject constructor(
    private val remote: QuoteDetailRemote,
) : QuoteDetailQueryPort {

    override fun find(quoteId: String): QuoteDetail =
        remote.find(quoteId)
}
