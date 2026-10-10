package de.littletrotter.infrastructure.quote

import com.fasterxml.jackson.core.type.TypeReference
import de.littletrotter.application.quote.QuoteNotFoundException
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.inbound.QuoteHeaderField
import de.littletrotter.application.quote.port.outbound.QuoteHeaderFieldRemote
import de.littletrotter.infrastructure.quote.acl.QuoteViewTranslator
import de.littletrotter.shared.quote.QuoteApiPaths
import de.littletrotter.shared.quote.QuoteHeaderFieldView
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/** Header fields against `GET /quote/api/quotes/{id}/header-fields`. */
@LookupIfProperty(name = "quote.enabled", stringValue = "true")
@ApplicationScoped
class HttpQuoteHeaderFieldQueryAdapter @Inject constructor(
    private val apiClient: QuoteApiClient,
) : QuoteHeaderFieldRemote {

    @Retry(
        retryOn = [QuoteServiceException::class],
        abortOn = [QuoteNotFoundException::class],
        maxRetries = 2,
        delay = 300,
        delayUnit = ChronoUnit.MILLIS,
    )
    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun headerFields(quoteId: String): List<QuoteHeaderField> =
        apiClient.get(
            QuoteRoutes.of(QuoteApiPaths.HEADER_FIELDS, quoteId),
            emptyList(),
            "header fields",
            object : TypeReference<List<QuoteHeaderFieldView>>() {},
        ).map(QuoteViewTranslator::toHeaderField)
}
