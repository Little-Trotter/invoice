package de.littletrotter.infrastructure.quote

import com.fasterxml.jackson.core.type.TypeReference
import de.littletrotter.application.quote.QuoteNotFoundException
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.inbound.QuoteListCriteria
import de.littletrotter.application.quote.port.outbound.QuoteListRemote
import de.littletrotter.application.quote.port.inbound.QuotePage
import de.littletrotter.infrastructure.quote.acl.QuoteRequestTranslator
import de.littletrotter.infrastructure.quote.acl.QuoteViewTranslator
import de.littletrotter.shared.quote.QuoteApiPaths
import de.littletrotter.shared.quote.QuotePageView
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/** Offer list against `GET /quote/api/quotes`; idempotent, so it retries before a user waiting on the list sees an error. */
@LookupIfProperty(name = "quote.enabled", stringValue = "true")
@ApplicationScoped
class HttpQuoteListQueryAdapter @Inject constructor(
    private val apiClient: QuoteApiClient,
) : QuoteListRemote {

    @Retry(
        retryOn = [QuoteServiceException::class],
        abortOn = [QuoteNotFoundException::class],
        maxRetries = 2,
        delay = 300,
        delayUnit = ChronoUnit.MILLIS,
    )
    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun findPage(criteria: QuoteListCriteria): QuotePage =
        QuoteViewTranslator.toPage(
            apiClient.get(
                QuoteApiPaths.QUOTES,
                QuoteRequestTranslator.toListParameters(criteria),
                "offer list",
                object : TypeReference<QuotePageView>() {},
            ),
        )
}
