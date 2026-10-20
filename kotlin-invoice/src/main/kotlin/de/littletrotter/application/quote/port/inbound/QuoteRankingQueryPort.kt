package de.littletrotter.application.quote.port.inbound

/** Reads the customers or articles with the highest sent volume in a selection, best first. */
fun interface QuoteRankingQueryPort {

    fun ranking(kind: QuoteRankingKind, selection: QuoteStatisticsSelection, limit: Int): List<QuoteRankingRow>
}
