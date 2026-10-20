package de.littletrotter.infrastructure.quote

import com.fasterxml.jackson.core.type.TypeReference
import de.littletrotter.application.quote.port.outbound.SubmitQuoteForReviewRemote
import de.littletrotter.shared.quote.QuoteApiPaths
import de.littletrotter.shared.quote.QuoteNumberAnswer
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/** `POST /quote/api/quotes/{id}/submit`; the answer carries the number the offer received. */
@LookupIfProperty(name = "quote.enabled", stringValue = "true")
@ApplicationScoped
class HttpSubmitQuoteAdapter @Inject constructor(
    private val apiClient: QuoteApiClient,
) : SubmitQuoteForReviewRemote {

    @Timeout(value = 30_000, unit = ChronoUnit.MILLIS)
    override fun submit(quoteId: String): String =
        apiClient.post(
            QuoteRoutes.of(QuoteApiPaths.SUBMIT, quoteId),
            null,
            "submit answer",
            object : TypeReference<QuoteNumberAnswer>() {},
        ).number
}
