package de.littletrotter.infrastructure.quote

import com.fasterxml.jackson.core.type.TypeReference
import de.littletrotter.application.quote.port.outbound.AttachQuoteFileRemote
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentUpload
import de.littletrotter.application.quote.port.inbound.StoredQuoteFile
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

/** `POST /quote/api/quotes/{id}/attachments`; the file travels base64-encoded inside the JSON body. */
@LookupIfProperty(name = "quote.enabled", stringValue = "true")
@ApplicationScoped
class HttpAttachQuoteFileAdapter @Inject constructor(
    private val apiClient: QuoteApiClient,
) : AttachQuoteFileRemote {

    @Timeout(value = 30_000, unit = ChronoUnit.MILLIS)
    override fun attach(quoteId: String, upload: QuoteAttachmentUpload): StoredQuoteFile =
        QuoteViewTranslator.toStoredFile(
            apiClient.post(
                QuoteRoutes.of(QuoteApiPaths.ATTACHMENTS, quoteId),
                QuoteWireCodec.encode(QuoteRequestTranslator.toAttachRequest(upload)),
                "attachment",
                object : TypeReference<QuoteIdAnswer>() {},
            ),
        )
}
