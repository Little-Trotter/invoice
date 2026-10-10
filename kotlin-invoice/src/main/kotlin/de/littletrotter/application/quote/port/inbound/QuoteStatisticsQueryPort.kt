package de.littletrotter.application.quote.port.inbound

/** Reads the status counts for the offer overview. */
fun interface QuoteStatisticsQueryPort {

    fun statistics(): QuoteStatistics
}
