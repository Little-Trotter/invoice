package de.littletrotter.infrastructure.quote

import com.fasterxml.jackson.core.type.TypeReference
import de.littletrotter.application.quote.QuoteNotFoundException
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.inbound.QuoteStatistics
import de.littletrotter.application.quote.port.outbound.QuoteStatisticsRemote
import de.littletrotter.infrastructure.quote.acl.QuoteViewTranslator
import de.littletrotter.shared.quote.QuoteApiPaths
import de.littletrotter.shared.quote.QuoteStatisticsView
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/** Status counts against `GET /quote/api/quotes/statistics`. */
@LookupIfProperty(name = "quote.enabled", stringValue = "true")
@ApplicationScoped
class HttpQuoteStatisticsQueryAdapter @Inject constructor(
    private val apiClient: QuoteApiClient,
) : QuoteStatisticsRemote {

    @Retry(
        retryOn = [QuoteServiceException::class],
        abortOn = [QuoteNotFoundException::class],
        maxRetries = 2,
        delay = 300,
        delayUnit = ChronoUnit.MILLIS,
    )
    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun statistics(): QuoteStatistics =
        QuoteViewTranslator.toStatistics(
            apiClient.get(
                QuoteApiPaths.QUOTES + QuoteApiPaths.STATISTICS,
                emptyList(),
                "offer statistics",
                object : TypeReference<QuoteStatisticsView>() {},
            ),
        )
}
