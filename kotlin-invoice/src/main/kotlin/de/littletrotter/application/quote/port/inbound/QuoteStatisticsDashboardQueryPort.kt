package de.littletrotter.application.quote.port.inbound

/** Reads the figures of the offer statistics page for one selection of period and filters. */
fun interface QuoteStatisticsDashboardQueryPort {

    fun dashboard(selection: QuoteStatisticsSelection): QuoteDashboard
}
