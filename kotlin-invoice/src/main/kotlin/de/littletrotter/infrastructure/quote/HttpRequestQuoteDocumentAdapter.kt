package de.littletrotter.infrastructure.quote

import com.fasterxml.jackson.core.type.TypeReference
import de.littletrotter.application.quote.port.inbound.QuoteDocumentMark
import de.littletrotter.application.quote.port.outbound.RequestQuoteDocumentRemote
import de.littletrotter.infrastructure.quote.acl.QuoteRequestTranslator
import de.littletrotter.shared.quote.QuoteApiPaths
import de.littletrotter.shared.quote.QuoteIdAnswer
import de.littletrotter.shared.quote.QuoteWireCodec
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/** `POST /quote/api/quotes/{id}/documents`; the answer carries the id of the pending document. */
@LookupIfProperty(name = "quote.enabled", stringValue = "true")
@ApplicationScoped
class HttpRequestQuoteDocumentAdapter @Inject constructor(
    private val apiClient: QuoteApiClient,
) : RequestQuoteDocumentRemote {

    @Timeout(value = 30_000, unit = ChronoUnit.MILLIS)
    override fun request(quoteId: String, mark: QuoteDocumentMark): String =
        apiClient.post(
            QuoteRoutes.of(QuoteApiPaths.DOCUMENTS, quoteId),
            QuoteWireCodec.encode(QuoteRequestTranslator.toDocumentRequest(mark)),
            "document request",
            object : TypeReference<QuoteIdAnswer>() {},
        ).id
}
