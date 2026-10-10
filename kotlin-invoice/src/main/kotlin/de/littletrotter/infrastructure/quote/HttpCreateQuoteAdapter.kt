package de.littletrotter.infrastructure.quote

import com.fasterxml.jackson.core.type.TypeReference
import de.littletrotter.application.quote.port.outbound.CreateQuoteRemote
import de.littletrotter.shared.quote.CreateQuoteRequest
import de.littletrotter.shared.quote.QuoteApiPaths
import de.littletrotter.shared.quote.QuoteIdAnswer
import de.littletrotter.shared.quote.QuoteWireCodec
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/** `POST /quote/api/quotes`: starts a draft. No retry: a repeated create after a lost answer would leave a second draft. */
@LookupIfProperty(name = "quote.enabled", stringValue = "true")
@ApplicationScoped
class HttpCreateQuoteAdapter @Inject constructor(
    private val apiClient: QuoteApiClient,
) : CreateQuoteRemote {

    @Timeout(value = 30_000, unit = ChronoUnit.MILLIS)
    override fun create(customerId: String): String =
        apiClient.post(
            QuoteApiPaths.QUOTES,
            QuoteWireCodec.encode(CreateQuoteRequest(customerId)),
            "created offer",
            object : TypeReference<QuoteIdAnswer>() {},
        ).id
}
