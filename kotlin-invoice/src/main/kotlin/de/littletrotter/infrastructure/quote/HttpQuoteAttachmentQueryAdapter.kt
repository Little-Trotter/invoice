package de.littletrotter.infrastructure.quote

import com.fasterxml.jackson.core.type.TypeReference
import de.littletrotter.application.quote.QuoteNotFoundException
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.inbound.QuoteAttachment
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentContent
import de.littletrotter.application.quote.port.outbound.QuoteAttachmentRemote
import de.littletrotter.infrastructure.quote.acl.QuoteViewTranslator
import de.littletrotter.shared.quote.QuoteApiPaths
import de.littletrotter.shared.quote.QuoteAttachmentView
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/** Attachment list and download against `GET /quote/api/quotes/{id}/attachments[/{aid}/content]`. */
@LookupIfProperty(name = "quote.enabled", stringValue = "true")
@ApplicationScoped
class HttpQuoteAttachmentQueryAdapter @Inject constructor(
    private val apiClient: QuoteApiClient,
) : QuoteAttachmentRemote {

    @Retry(
        retryOn = [QuoteServiceException::class],
        abortOn = [QuoteNotFoundException::class],
        maxRetries = 2,
        delay = 300,
        delayUnit = ChronoUnit.MILLIS,
    )
    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun attachments(quoteId: String): List<QuoteAttachment> =
        apiClient.get(
            QuoteRoutes.of(QuoteApiPaths.ATTACHMENTS, quoteId),
            emptyList(),
            "attachment list",
            object : TypeReference<List<QuoteAttachmentView>>() {},
        ).map(QuoteViewTranslator::toAttachment)

    @Retry(
        retryOn = [QuoteServiceException::class],
        abortOn = [QuoteNotFoundException::class],
        maxRetries = 2,
        delay = 300,
        delayUnit = ChronoUnit.MILLIS,
    )
    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun content(quoteId: String, attachmentId: String): QuoteAttachmentContent {
        val answer = apiClient.getBytes(QuoteRoutes.of(QuoteApiPaths.ATTACHMENT_CONTENT, quoteId, attachmentId))
        return QuoteAttachmentContent(
            fileName = downloadFileName(answer.contentDisposition, DEFAULT_FILE_NAME),
            mimeType = answer.contentType?.takeIf { it.isNotBlank() } ?: DEFAULT_MIME_TYPE,
            bytes = answer.bytes,
        )
    }
}

private const val DEFAULT_FILE_NAME = "attachment"
private const val DEFAULT_MIME_TYPE = "application/octet-stream"
