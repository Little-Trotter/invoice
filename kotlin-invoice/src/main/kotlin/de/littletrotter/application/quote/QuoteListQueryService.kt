package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.QuoteListCriteria
import de.littletrotter.application.quote.port.inbound.QuoteListQueryPort
import de.littletrotter.application.quote.port.inbound.QuotePage
import de.littletrotter.application.quote.port.outbound.QuoteListRemote
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Application service of `QuoteListQueryPort`; the call itself is the outbound `QuoteListRemote`. */
@ApplicationScoped
class QuoteListQueryService @Inject constructor(
    private val remote: QuoteListRemote,
) : QuoteListQueryPort {

    override fun findPage(criteria: QuoteListCriteria): QuotePage =
        remote.findPage(criteria)
}
