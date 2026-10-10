package de.littletrotter.infrastructure.quote

import com.fasterxml.jackson.core.type.TypeReference
import de.littletrotter.application.quote.QuoteNotFoundException
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.inbound.AttachmentLibraryEntry
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentContent
import de.littletrotter.application.quote.port.outbound.AttachmentLibraryRemote
import de.littletrotter.infrastructure.quote.acl.QuoteViewTranslator
import de.littletrotter.shared.quote.AttachmentLibraryEntryView
import de.littletrotter.shared.quote.QuoteApiPaths
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/** Pool list and version download against `GET /quote/api/attachment-library[/{id}/versions/{vid}/content]`. */
@LookupIfProperty(name = "quote.enabled", stringValue = "true")
@ApplicationScoped
class HttpAttachmentLibraryQueryAdapter @Inject constructor(
    private val apiClient: QuoteApiClient,
) : AttachmentLibraryRemote {

    @Retry(
        retryOn = [QuoteServiceException::class],
        abortOn = [QuoteNotFoundException::class],
        maxRetries = 2,
        delay = 300,
        delayUnit = ChronoUnit.MILLIS,
    )
    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun entries(): List<AttachmentLibraryEntry> =
        apiClient.get(
            QuoteRoutes.library(),
            emptyList(),
            "attachment library",
            object : TypeReference<List<AttachmentLibraryEntryView>>() {},
        ).map(QuoteViewTranslator::toLibraryEntry)

    @Retry(
        retryOn = [QuoteServiceException::class],
        abortOn = [QuoteNotFoundException::class],
        maxRetries = 2,
        delay = 300,
        delayUnit = ChronoUnit.MILLIS,
    )
    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun versionContent(entryId: String, versionId: String): QuoteAttachmentContent {
        val answer = apiClient.getBytes(QuoteRoutes.library(QuoteApiPaths.LIBRARY_VERSION_CONTENT, entryId, versionId))
        return QuoteAttachmentContent(
            fileName = downloadFileName(answer.contentDisposition, DEFAULT_FILE_NAME),
            mimeType = answer.contentType?.takeIf { it.isNotBlank() } ?: DEFAULT_MIME_TYPE,
            bytes = answer.bytes,
        )
    }
}

private const val DEFAULT_FILE_NAME = "attachment"
private const val DEFAULT_MIME_TYPE = "application/octet-stream"
