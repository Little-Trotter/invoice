package de.littletrotter.infrastructure.quote

import de.littletrotter.application.quote.port.inbound.AssignQuoteAttachmentCommand
import de.littletrotter.application.quote.port.outbound.AssignQuoteAttachmentRemote
import de.littletrotter.shared.quote.AssignQuoteAttachmentRequest
import de.littletrotter.shared.quote.QuoteApiPaths
import de.littletrotter.shared.quote.QuoteWireCodec
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/** `PUT /quote/api/quotes/{id}/attachments/{aid}/item`; a null item id releases the attachment from its line. */
@LookupIfProperty(name = "quote.enabled", stringValue = "true")
@ApplicationScoped
class HttpAssignQuoteAttachmentAdapter @Inject constructor(
    private val apiClient: QuoteApiClient,
) : AssignQuoteAttachmentRemote {

    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun assign(command: AssignQuoteAttachmentCommand) =
        apiClient.put(
            QuoteRoutes.of(QuoteApiPaths.ATTACHMENT_ITEM, command.quoteId, command.attachmentId),
            QuoteWireCodec.encode(AssignQuoteAttachmentRequest(command.itemId)),
        )
}
