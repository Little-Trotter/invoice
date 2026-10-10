package de.littletrotter.infrastructure.quote

import de.littletrotter.application.quote.port.outbound.RejectQuoteRemote
import de.littletrotter.shared.quote.QuoteApiPaths
import de.littletrotter.shared.quote.QuoteWireCodec
import de.littletrotter.shared.quote.RejectQuoteRequest
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/** `POST /quote/api/quotes/{id}/reject`. */
@LookupIfProperty(name = "quote.enabled", stringValue = "true")
@ApplicationScoped
class HttpRejectQuoteAdapter @Inject constructor(
    private val apiClient: QuoteApiClient,
) : RejectQuoteRemote {

    @Timeout(value = 30_000, unit = ChronoUnit.MILLIS)
    override fun reject(quoteId: String, reason: String) =
        apiClient.postNoContent(
            QuoteRoutes.of(QuoteApiPaths.REJECT, quoteId),
            QuoteWireCodec.encode(RejectQuoteRequest(reason)),
        )
}
