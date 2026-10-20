package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.RemoveQuoteAttachmentUseCase
import de.littletrotter.application.quote.port.outbound.RemoveQuoteAttachmentRemote
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Application service of `RemoveQuoteAttachmentUseCase`; the call itself is the outbound `RemoveQuoteAttachmentRemote`. */
@ApplicationScoped
class RemoveQuoteAttachmentService @Inject constructor(
    private val remote: RemoveQuoteAttachmentRemote,
) : RemoveQuoteAttachmentUseCase {

    override fun remove(quoteId: String, attachmentId: String) =
        remote.remove(quoteId, attachmentId)
}
