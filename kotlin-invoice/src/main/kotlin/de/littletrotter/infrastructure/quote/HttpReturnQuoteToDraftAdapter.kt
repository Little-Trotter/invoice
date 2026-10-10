package de.littletrotter.infrastructure.quote

import de.littletrotter.application.quote.port.outbound.ReturnQuoteToDraftRemote
import de.littletrotter.shared.quote.QuoteApiPaths
import de.littletrotter.shared.quote.QuoteWireCodec
import de.littletrotter.shared.quote.ReturnQuoteToDraftRequest
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/** `POST /quote/api/quotes/{id}/return-to-draft`. */
@LookupIfProperty(name = "quote.enabled", stringValue = "true")
@ApplicationScoped
class HttpReturnQuoteToDraftAdapter @Inject constructor(
    private val apiClient: QuoteApiClient,
) : ReturnQuoteToDraftRemote {

    @Timeout(value = 30_000, unit = ChronoUnit.MILLIS)
    override fun returnToDraft(quoteId: String, note: String) =
        apiClient.postNoContent(
            QuoteRoutes.of(QuoteApiPaths.RETURN_TO_DRAFT, quoteId),
            QuoteWireCodec.encode(ReturnQuoteToDraftRequest(note)),
        )
}
