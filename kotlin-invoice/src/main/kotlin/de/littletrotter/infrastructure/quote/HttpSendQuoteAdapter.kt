package de.littletrotter.infrastructure.quote

import com.fasterxml.jackson.core.type.TypeReference
import de.littletrotter.application.quote.port.inbound.QuoteDispatchHandover
import de.littletrotter.application.quote.port.outbound.SendQuoteRemote
import de.littletrotter.infrastructure.quote.acl.QuoteViewTranslator
import de.littletrotter.shared.quote.QuoteApiPaths
import de.littletrotter.shared.quote.QuoteDispatchHandoverView
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/** `POST /quote/api/quotes/{id}/send`; the answer carries the mail compose handover or the pending copy. */
@LookupIfProperty(name = "quote.enabled", stringValue = "true")
@ApplicationScoped
class HttpSendQuoteAdapter @Inject constructor(
    private val apiClient: QuoteApiClient,
) : SendQuoteRemote {

    @Timeout(value = 30_000, unit = ChronoUnit.MILLIS)
    override fun send(quoteId: String): QuoteDispatchHandover =
        QuoteViewTranslator.toDispatchHandover(
            apiClient.post(
                QuoteRoutes.of(QuoteApiPaths.SEND, quoteId),
                null,
                "send answer",
                object : TypeReference<QuoteDispatchHandoverView>() {},
            ),
        )
}
