package de.littletrotter.application.quote.port.outbound

import de.littletrotter.application.quote.port.inbound.QuoteDashboard
import de.littletrotter.application.quote.port.inbound.QuoteStatisticsSelection

/** Remote side of `QuoteStatisticsDashboardQueryPort`: the offer component call behind the application service. */
fun interface QuoteStatisticsDashboardRemote {

    fun dashboard(selection: QuoteStatisticsSelection): QuoteDashboard
}
