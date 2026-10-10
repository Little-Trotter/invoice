package de.littletrotter.infrastructure.quote

import de.littletrotter.application.quote.port.outbound.RemoveQuoteAttachmentRemote
import de.littletrotter.shared.quote.QuoteApiPaths
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/** `DELETE /quote/api/quotes/{id}/attachments/{aid}`. */
@LookupIfProperty(name = "quote.enabled", stringValue = "true")
@ApplicationScoped
class HttpRemoveQuoteAttachmentAdapter @Inject constructor(
    private val apiClient: QuoteApiClient,
) : RemoveQuoteAttachmentRemote {

    @Timeout(value = 30_000, unit = ChronoUnit.MILLIS)
    override fun remove(quoteId: String, attachmentId: String) =
        apiClient.delete(QuoteRoutes.of(QuoteApiPaths.ATTACHMENT, quoteId, attachmentId))
}
