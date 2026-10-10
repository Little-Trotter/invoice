package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.QuoteAttachment
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentContent
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentQueryPort
import de.littletrotter.application.quote.port.outbound.QuoteAttachmentRemote
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Application service of `QuoteAttachmentQueryPort`; the call itself is the outbound `QuoteAttachmentRemote`. */
@ApplicationScoped
class QuoteAttachmentQueryService @Inject constructor(
    private val remote: QuoteAttachmentRemote,
) : QuoteAttachmentQueryPort {

    override fun attachments(quoteId: String): List<QuoteAttachment> =
        remote.attachments(quoteId)

    override fun content(quoteId: String, attachmentId: String): QuoteAttachmentContent =
        remote.content(quoteId, attachmentId)
}
