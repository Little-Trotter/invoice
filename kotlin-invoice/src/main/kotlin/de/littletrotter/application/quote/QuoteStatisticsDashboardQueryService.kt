package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.QuoteDashboard
import de.littletrotter.application.quote.port.inbound.QuoteStatisticsDashboardQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteStatisticsSelection
import de.littletrotter.application.quote.port.outbound.QuoteStatisticsDashboardRemote
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Application service of `QuoteStatisticsDashboardQueryPort`; the call itself is the outbound `QuoteStatisticsDashboardRemote`. */
@ApplicationScoped
class QuoteStatisticsDashboardQueryService @Inject constructor(
    private val remote: QuoteStatisticsDashboardRemote,
) : QuoteStatisticsDashboardQueryPort {

    override fun dashboard(selection: QuoteStatisticsSelection): QuoteDashboard =
        remote.dashboard(selection)
}
