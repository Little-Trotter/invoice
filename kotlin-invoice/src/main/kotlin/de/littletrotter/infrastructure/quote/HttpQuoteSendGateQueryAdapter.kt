package de.littletrotter.infrastructure.quote

import com.fasterxml.jackson.core.type.TypeReference
import de.littletrotter.application.quote.QuoteNotFoundException
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.inbound.QuoteSendGateState
import de.littletrotter.application.quote.port.outbound.QuoteSendGateRemote
import de.littletrotter.shared.quote.QuoteApiPaths
import de.littletrotter.shared.quote.QuoteSendGateView
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/** The send gate of one offer against `GET /quote/api/quotes/{id}/send-gate`. */
@LookupIfProperty(name = "quote.enabled", stringValue = "true")
@ApplicationScoped
class HttpQuoteSendGateQueryAdapter @Inject constructor(
    private val apiClient: QuoteApiClient,
) : QuoteSendGateRemote {

    @Retry(
        retryOn = [QuoteServiceException::class],
        abortOn = [QuoteNotFoundException::class],
        maxRetries = 2,
        delay = 300,
        delayUnit = ChronoUnit.MILLIS,
    )
    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun gate(quoteId: String): QuoteSendGateState {
        val view = apiClient.get(
            QuoteRoutes.of(QuoteApiPaths.SEND_GATE, quoteId),
            emptyList(),
            "send gate",
            object : TypeReference<QuoteSendGateView>() {},
        )
        return sendGateStateOf(view.gate)
            ?: throw QuoteServiceException("The offer component sent an unknown send gate '${view.gate}'")
    }
}
