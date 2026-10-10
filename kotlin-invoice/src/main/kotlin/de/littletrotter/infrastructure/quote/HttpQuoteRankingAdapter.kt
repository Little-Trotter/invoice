package de.littletrotter.infrastructure.quote

import com.fasterxml.jackson.core.type.TypeReference
import de.littletrotter.application.quote.QuoteNotFoundException
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.inbound.QuoteRankingKind
import de.littletrotter.application.quote.port.inbound.QuoteRankingRow
import de.littletrotter.application.quote.port.inbound.QuoteStatisticsSelection
import de.littletrotter.application.quote.port.outbound.QuoteRankingRemote
import de.littletrotter.infrastructure.quote.acl.QuoteRequestTranslator
import de.littletrotter.infrastructure.quote.acl.QuoteViewTranslator
import de.littletrotter.shared.quote.QuoteApiPaths
import de.littletrotter.shared.quote.QuoteRankingRowView
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/** Customer and article rankings against `GET /quote/api/quotes/statistics/ranking`. */
@LookupIfProperty(name = "quote.enabled", stringValue = "true")
@ApplicationScoped
class HttpQuoteRankingAdapter @Inject constructor(
    private val apiClient: QuoteApiClient,
) : QuoteRankingRemote {

    @Retry(
        retryOn = [QuoteServiceException::class],
        abortOn = [QuoteNotFoundException::class],
        maxRetries = 2,
        delay = 300,
        delayUnit = ChronoUnit.MILLIS,
    )
    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun ranking(kind: QuoteRankingKind, selection: QuoteStatisticsSelection, limit: Int): List<QuoteRankingRow> =
        apiClient.get(
            QuoteApiPaths.QUOTES + QuoteApiPaths.STATISTICS_RANKING,
            QuoteRequestTranslator.toRankingParameters(kind, selection, limit),
            "offer ranking",
            object : TypeReference<List<QuoteRankingRowView>>() {},
        ).map(QuoteViewTranslator::toRankingRow)
}
