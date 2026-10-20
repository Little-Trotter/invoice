package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.QuoteByNumberQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteDetail
import de.littletrotter.application.quote.port.outbound.QuoteByNumberRemote
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Application service of `QuoteByNumberQueryPort`; the call itself is the outbound `QuoteByNumberRemote`. */
@ApplicationScoped
class QuoteByNumberQueryService @Inject constructor(
    private val remote: QuoteByNumberRemote,
) : QuoteByNumberQueryPort {

    override fun find(number: String): QuoteDetail =
        remote.find(number)
}
