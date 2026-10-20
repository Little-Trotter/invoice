package de.littletrotter.infrastructure.quote

import com.fasterxml.jackson.core.type.TypeReference
import de.littletrotter.application.quote.QuoteNotFoundException
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.outbound.QuoteCustomerLookupRemote
import de.littletrotter.application.quote.port.inbound.QuoteCustomerOption
import de.littletrotter.infrastructure.quote.acl.QuoteViewTranslator
import de.littletrotter.shared.quote.QuoteApiPaths
import de.littletrotter.shared.quote.QuoteCustomerOptionView
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/** Customer picker against `GET /quote/api/lookup/customers`. */
@LookupIfProperty(name = "quote.enabled", stringValue = "true")
@ApplicationScoped
class HttpQuoteCustomerLookupQueryAdapter @Inject constructor(
    private val apiClient: QuoteApiClient,
) : QuoteCustomerLookupRemote {

    @Retry(
        retryOn = [QuoteServiceException::class],
        abortOn = [QuoteNotFoundException::class],
        maxRetries = 2,
        delay = 300,
        delayUnit = ChronoUnit.MILLIS,
    )
    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun customers(text: String, limit: Int): List<QuoteCustomerOption> =
        apiClient.get(
            QuoteApiPaths.LOOKUP_CUSTOMERS,
            listOf("text" to text, "limit" to limit.toString()),
            "customer lookup",
            object : TypeReference<List<QuoteCustomerOptionView>>() {},
        ).map(QuoteViewTranslator::toCustomerOption)
}
