package de.littletrotter.infrastructure.quote

import de.littletrotter.application.quote.port.outbound.CancelQuoteRemote
import de.littletrotter.shared.quote.CancelQuoteRequest
import de.littletrotter.shared.quote.QuoteApiPaths
import de.littletrotter.shared.quote.QuoteWireCodec
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/** `POST /quote/api/quotes/{id}/cancel`. */
@LookupIfProperty(name = "quote.enabled", stringValue = "true")
@ApplicationScoped
class HttpCancelQuoteAdapter @Inject constructor(
    private val apiClient: QuoteApiClient,
) : CancelQuoteRemote {

    @Timeout(value = 30_000, unit = ChronoUnit.MILLIS)
    override fun cancel(quoteId: String, reason: String) =
        apiClient.postNoContent(
            QuoteRoutes.of(QuoteApiPaths.CANCEL, quoteId),
            QuoteWireCodec.encode(CancelQuoteRequest(reason)),
        )
}
