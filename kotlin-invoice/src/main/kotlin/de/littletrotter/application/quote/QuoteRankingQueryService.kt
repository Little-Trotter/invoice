package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.QuoteRankingKind
import de.littletrotter.application.quote.port.inbound.QuoteRankingQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteRankingRow
import de.littletrotter.application.quote.port.inbound.QuoteStatisticsSelection
import de.littletrotter.application.quote.port.outbound.QuoteRankingRemote
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Application service of `QuoteRankingQueryPort`; the call itself is the outbound `QuoteRankingRemote`. */
@ApplicationScoped
class QuoteRankingQueryService @Inject constructor(
    private val remote: QuoteRankingRemote,
) : QuoteRankingQueryPort {

    override fun ranking(kind: QuoteRankingKind, selection: QuoteStatisticsSelection, limit: Int): List<QuoteRankingRow> =
        remote.ranking(kind, selection, limit)
}
