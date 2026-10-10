package de.littletrotter.infrastructure.quote

import de.littletrotter.application.quote.port.outbound.AddQuoteNoteRemote
import de.littletrotter.shared.quote.AddQuoteNoteRequest
import de.littletrotter.shared.quote.QuoteApiPaths
import de.littletrotter.shared.quote.QuoteWireCodec
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/** `POST /quote/api/quotes/{id}/notes`. */
@LookupIfProperty(name = "quote.enabled", stringValue = "true")
@ApplicationScoped
class HttpAddQuoteNoteAdapter @Inject constructor(
    private val apiClient: QuoteApiClient,
) : AddQuoteNoteRemote {

    @Timeout(value = 30_000, unit = ChronoUnit.MILLIS)
    override fun addNote(quoteId: String, text: String) =
        apiClient.postNoContent(
            QuoteRoutes.of(QuoteApiPaths.NOTES, quoteId),
            QuoteWireCodec.encode(AddQuoteNoteRequest(text)),
        )
}
