package de.littletrotter.infrastructure.quote

import com.fasterxml.jackson.core.type.TypeReference
import de.littletrotter.application.quote.port.inbound.AttachmentLibraryFile
import de.littletrotter.application.quote.port.inbound.NewAttachmentLibraryEntry
import de.littletrotter.application.quote.port.inbound.StoredQuoteFile
import de.littletrotter.application.quote.port.outbound.MaintainAttachmentLibraryRemote
import de.littletrotter.infrastructure.quote.acl.QuoteRequestTranslator
import de.littletrotter.infrastructure.quote.acl.QuoteViewTranslator
import de.littletrotter.shared.quote.QuoteApiPaths
import de.littletrotter.shared.quote.QuoteIdAnswer
import de.littletrotter.shared.quote.QuoteWireCodec
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/** Pool maintenance: `POST /quote/api/attachment-library`, `…/{id}/versions` and `…/{id}/archive`; files travel base64-encoded in JSON. */
@LookupIfProperty(name = "quote.enabled", stringValue = "true")
@ApplicationScoped
class HttpMaintainAttachmentLibraryAdapter @Inject constructor(
    private val apiClient: QuoteApiClient,
) : MaintainAttachmentLibraryRemote {

    @Timeout(value = 30_000, unit = ChronoUnit.MILLIS)
    override fun create(entry: NewAttachmentLibraryEntry): StoredQuoteFile =
        QuoteViewTranslator.toStoredFile(
            apiClient.post(
                QuoteRoutes.library(),
                QuoteWireCodec.encode(QuoteRequestTranslator.toCreateEntryRequest(entry)),
                "library entry",
                object : TypeReference<QuoteIdAnswer>() {},
            ),
        )

    @Timeout(value = 30_000, unit = ChronoUnit.MILLIS)
    override fun addVersion(entryId: String, file: AttachmentLibraryFile): StoredQuoteFile =
        QuoteViewTranslator.toStoredFile(
            apiClient.post(
                QuoteRoutes.library(QuoteApiPaths.LIBRARY_VERSIONS, entryId),
                QuoteWireCodec.encode(QuoteRequestTranslator.toAddVersionRequest(file)),
                "library version",
                object : TypeReference<QuoteIdAnswer>() {},
            ),
        )

    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun archive(entryId: String) =
        apiClient.postNoContent(QuoteRoutes.library(QuoteApiPaths.LIBRARY_ARCHIVE, entryId), null)
}
