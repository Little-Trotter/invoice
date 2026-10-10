package de.littletrotter.infrastructure.quote

import de.littletrotter.application.quote.port.outbound.EditQuoteItemsRemote
import de.littletrotter.application.quote.port.inbound.QuoteItemInput
import de.littletrotter.infrastructure.quote.acl.QuoteRequestTranslator
import de.littletrotter.shared.quote.QuoteApiPaths
import de.littletrotter.shared.quote.QuoteWireCodec
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/** `PUT /quote/api/quotes/{id}/items`. */
@LookupIfProperty(name = "quote.enabled", stringValue = "true")
@ApplicationScoped
class HttpEditQuoteItemsAdapter @Inject constructor(
    private val apiClient: QuoteApiClient,
) : EditQuoteItemsRemote {

    @Timeout(value = 30_000, unit = ChronoUnit.MILLIS)
    override fun editItems(quoteId: String, lines: List<QuoteItemInput>) =
        apiClient.put(
            QuoteRoutes.of(QuoteApiPaths.ITEMS, quoteId),
            QuoteWireCodec.encode(QuoteRequestTranslator.toItemsRequest(lines)),
        )
}
