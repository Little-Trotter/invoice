package de.littletrotter.infrastructure.quote

import com.fasterxml.jackson.core.type.TypeReference
import de.littletrotter.application.quote.QuoteNotFoundException
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.inbound.QuoteDashboard
import de.littletrotter.application.quote.port.inbound.QuoteStatisticsSelection
import de.littletrotter.application.quote.port.outbound.QuoteStatisticsDashboardRemote
import de.littletrotter.infrastructure.quote.acl.QuoteRequestTranslator
import de.littletrotter.infrastructure.quote.acl.QuoteViewTranslator
import de.littletrotter.shared.quote.QuoteApiPaths
import de.littletrotter.shared.quote.QuoteDashboardView
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/** Statistics figures against `GET /quote/api/quotes/statistics/dashboard`. */
@LookupIfProperty(name = "quote.enabled", stringValue = "true")
@ApplicationScoped
class HttpQuoteStatisticsDashboardAdapter @Inject constructor(
    private val apiClient: QuoteApiClient,
) : QuoteStatisticsDashboardRemote {

    @Retry(
        retryOn = [QuoteServiceException::class],
        abortOn = [QuoteNotFoundException::class],
        maxRetries = 2,
        delay = 300,
        delayUnit = ChronoUnit.MILLIS,
    )
    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun dashboard(selection: QuoteStatisticsSelection): QuoteDashboard =
        QuoteViewTranslator.toDashboard(
            apiClient.get(
                QuoteApiPaths.QUOTES + QuoteApiPaths.STATISTICS_DASHBOARD,
                QuoteRequestTranslator.toStatisticsParameters(selection),
                "offer statistics dashboard",
                object : TypeReference<QuoteDashboardView>() {},
            ),
        )
}
