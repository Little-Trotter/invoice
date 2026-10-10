package de.littletrotter.application.quote.port.outbound

import de.littletrotter.application.quote.port.inbound.QuoteRankingKind
import de.littletrotter.application.quote.port.inbound.QuoteRankingRow
import de.littletrotter.application.quote.port.inbound.QuoteStatisticsSelection

/** Remote side of `QuoteRankingQueryPort`: the offer component call behind the application service. */
fun interface QuoteRankingRemote {

    fun ranking(kind: QuoteRankingKind, selection: QuoteStatisticsSelection, limit: Int): List<QuoteRankingRow>
}
