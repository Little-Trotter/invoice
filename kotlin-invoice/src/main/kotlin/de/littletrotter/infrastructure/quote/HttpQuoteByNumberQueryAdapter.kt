package de.littletrotter.infrastructure.quote

import com.fasterxml.jackson.core.type.TypeReference
import de.littletrotter.application.quote.QuoteNotFoundException
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.inbound.QuoteDetail
import de.littletrotter.application.quote.port.outbound.QuoteByNumberRemote
import de.littletrotter.infrastructure.quote.acl.QuoteViewTranslator
import de.littletrotter.shared.quote.QuoteDetailView
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/** One offer against `GET /quote/api/quotes/by-number/{number}`. */
@LookupIfProperty(name = "quote.enabled", stringValue = "true")
@ApplicationScoped
class HttpQuoteByNumberQueryAdapter @Inject constructor(
    private val apiClient: QuoteApiClient,
) : QuoteByNumberRemote {

    @Retry(
        retryOn = [QuoteServiceException::class],
        abortOn = [QuoteNotFoundException::class],
        maxRetries = 2,
        delay = 300,
        delayUnit = ChronoUnit.MILLIS,
    )
    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun find(number: String): QuoteDetail =
        QuoteViewTranslator.toDetail(
            apiClient.get(
                QuoteRoutes.byNumber(number),
                emptyList(),
                "offer by number",
                object : TypeReference<QuoteDetailView>() {},
            ),
        )
}
