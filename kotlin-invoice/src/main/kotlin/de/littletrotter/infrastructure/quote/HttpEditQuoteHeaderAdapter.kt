package de.littletrotter.infrastructure.quote

import de.littletrotter.application.quote.port.inbound.EditQuoteHeaderCommand
import de.littletrotter.application.quote.port.outbound.EditQuoteHeaderRemote
import de.littletrotter.infrastructure.quote.acl.QuoteRequestTranslator
import de.littletrotter.shared.quote.QuoteApiPaths
import de.littletrotter.shared.quote.QuoteWireCodec
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/** `PUT /quote/api/quotes/{id}/header`. */
@LookupIfProperty(name = "quote.enabled", stringValue = "true")
@ApplicationScoped
class HttpEditQuoteHeaderAdapter @Inject constructor(
    private val apiClient: QuoteApiClient,
) : EditQuoteHeaderRemote {

    @Timeout(value = 30_000, unit = ChronoUnit.MILLIS)
    override fun editHeader(command: EditQuoteHeaderCommand) =
        apiClient.put(
            QuoteRoutes.of(QuoteApiPaths.HEADER, command.quoteId),
            QuoteWireCodec.encode(QuoteRequestTranslator.toHeaderRequest(command)),
        )
}
