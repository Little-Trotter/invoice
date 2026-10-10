package de.littletrotter.infrastructure.quote

import de.littletrotter.application.quote.QuoteNotFoundException
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.inbound.QuoteDocumentContent
import de.littletrotter.application.quote.port.outbound.QuoteDocumentFileRemote
import de.littletrotter.shared.quote.QuoteApiPaths
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/** PDF and XML download against `GET /quote/api/quotes/{id}/documents/{did}/pdf` and `…/xml`. */
@LookupIfProperty(name = "quote.enabled", stringValue = "true")
@ApplicationScoped
class HttpQuoteDocumentFileQueryAdapter @Inject constructor(
    private val apiClient: QuoteApiClient,
) : QuoteDocumentFileRemote {

    @Retry(
        retryOn = [QuoteServiceException::class],
        abortOn = [QuoteNotFoundException::class],
        maxRetries = 2,
        delay = 300,
        delayUnit = ChronoUnit.MILLIS,
    )
    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun pdf(quoteId: String, documentId: String): QuoteDocumentContent =
        download(QuoteRoutes.of(QuoteApiPaths.DOCUMENT_PDF, quoteId, documentId), DEFAULT_PDF_NAME)

    @Retry(
        retryOn = [QuoteServiceException::class],
        abortOn = [QuoteNotFoundException::class],
        maxRetries = 2,
        delay = 300,
        delayUnit = ChronoUnit.MILLIS,
    )
    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun xml(quoteId: String, documentId: String): QuoteDocumentContent? =
        try {
            download(QuoteRoutes.of(QuoteApiPaths.DOCUMENT_XML, quoteId, documentId), DEFAULT_XML_NAME)
        } catch (expected: QuoteNotFoundException) {
            // A document without machine-readable data answers 404; that is an answer, not a failure.
            null
        }

    private fun download(path: String, fallbackName: String): QuoteDocumentContent {
        val answer = apiClient.getBytes(path)
        return QuoteDocumentContent(downloadFileName(answer.contentDisposition, fallbackName), answer.bytes)
    }
}

private const val DEFAULT_PDF_NAME = "offer.pdf"
private const val DEFAULT_XML_NAME = "offer.xml"
