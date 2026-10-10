package de.littletrotter.infrastructure.quote

import com.fasterxml.jackson.core.type.TypeReference
import de.littletrotter.application.quote.QuoteNotFoundException
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.inbound.QuoteLogEntry
import de.littletrotter.application.quote.port.inbound.QuoteLogKind
import de.littletrotter.application.quote.port.outbound.QuoteLogRemote
import de.littletrotter.infrastructure.quote.acl.QuoteRequestTranslator
import de.littletrotter.infrastructure.quote.acl.QuoteViewTranslator
import de.littletrotter.shared.quote.QuoteApiPaths
import de.littletrotter.shared.quote.QuoteLogEntryView
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/** Offer history against `GET /quote/api/quotes/{id}/log`. */
@LookupIfProperty(name = "quote.enabled", stringValue = "true")
@ApplicationScoped
class HttpQuoteLogQueryAdapter @Inject constructor(
    private val apiClient: QuoteApiClient,
) : QuoteLogRemote {

    @Retry(
        retryOn = [QuoteServiceException::class],
        abortOn = [QuoteNotFoundException::class],
        maxRetries = 2,
        delay = 300,
        delayUnit = ChronoUnit.MILLIS,
    )
    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun log(quoteId: String, kinds: Set<QuoteLogKind>): List<QuoteLogEntry> =
        apiClient.get(
            QuoteRoutes.of(QuoteApiPaths.LOG, quoteId),
            QuoteRequestTranslator.toLogParameters(kinds),
            "offer log",
            object : TypeReference<List<QuoteLogEntryView>>() {},
        ).map(QuoteViewTranslator::toLogEntry)
}
