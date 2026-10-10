package de.littletrotter.application.quote.port.outbound

import de.littletrotter.application.quote.port.inbound.QuoteStatistics

/** Remote side of `QuoteStatisticsQueryPort`: the offer component call behind the application service. */
fun interface QuoteStatisticsRemote {

    fun statistics(): QuoteStatistics
}
