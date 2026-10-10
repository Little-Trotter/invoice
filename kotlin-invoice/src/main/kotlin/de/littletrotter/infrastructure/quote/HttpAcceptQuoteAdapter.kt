package de.littletrotter.infrastructure.quote

import de.littletrotter.application.quote.port.outbound.AcceptQuoteRemote
import de.littletrotter.shared.quote.AcceptQuoteRequest
import de.littletrotter.shared.quote.QuoteApiPaths
import de.littletrotter.shared.quote.QuoteWireCodec
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** `POST /quote/api/quotes/{id}/accept`. */
@LookupIfProperty(name = "quote.enabled", stringValue = "true")
@ApplicationScoped
class HttpAcceptQuoteAdapter @Inject constructor(
    private val apiClient: QuoteApiClient,
) : AcceptQuoteRemote {

    @Timeout(value = 30_000, unit = ChronoUnit.MILLIS)
    override fun accept(quoteId: String, acceptedOn: LocalDate, customerOrderReference: String?) =
        apiClient.postNoContent(
            QuoteRoutes.of(QuoteApiPaths.ACCEPT, quoteId),
            QuoteWireCodec.encode(AcceptQuoteRequest(acceptedOn, customerOrderReference)),
        )
}
