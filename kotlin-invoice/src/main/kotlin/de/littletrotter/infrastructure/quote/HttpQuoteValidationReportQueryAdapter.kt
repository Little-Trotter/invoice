package de.littletrotter.infrastructure.quote

import de.littletrotter.application.quote.QuoteNotFoundException
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.inbound.QuoteValidationReport
import de.littletrotter.application.quote.port.outbound.QuoteValidationReportRemote
import de.littletrotter.infrastructure.quote.acl.QuoteViewTranslator
import de.littletrotter.shared.quote.QuoteApiPaths
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/** Check report against `GET /quote/api/quotes/{id}/documents/{did}/validation-report`. */
@LookupIfProperty(name = "quote.enabled", stringValue = "true")
@ApplicationScoped
class HttpQuoteValidationReportQueryAdapter @Inject constructor(
    private val apiClient: QuoteApiClient,
) : QuoteValidationReportRemote {

    @Retry(
        retryOn = [QuoteServiceException::class],
        abortOn = [QuoteNotFoundException::class],
        maxRetries = 2,
        delay = 300,
        delayUnit = ChronoUnit.MILLIS,
    )
    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun report(quoteId: String, documentId: String): QuoteValidationReport? =
        try {
            val raw = apiClient.getBytes(QuoteRoutes.of(QuoteApiPaths.DOCUMENT_VALIDATION_REPORT, quoteId, documentId))
            QuoteViewTranslator.toValidationReport(String(raw.bytes, Charsets.UTF_8))
        } catch (expected: QuoteNotFoundException) {
            // A document without a report answers 404; that is an answer, not a failure.
            null
        }
}
