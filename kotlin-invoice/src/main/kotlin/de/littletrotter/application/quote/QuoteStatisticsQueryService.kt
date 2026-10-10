package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.QuoteStatistics
import de.littletrotter.application.quote.port.inbound.QuoteStatisticsQueryPort
import de.littletrotter.application.quote.port.outbound.QuoteStatisticsRemote
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Application service of `QuoteStatisticsQueryPort`; the call itself is the outbound `QuoteStatisticsRemote`. */
@ApplicationScoped
class QuoteStatisticsQueryService @Inject constructor(
    private val remote: QuoteStatisticsRemote,
) : QuoteStatisticsQueryPort {

    override fun statistics(): QuoteStatistics =
        remote.statistics()
}
