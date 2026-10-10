package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.AttachQuoteFileUseCase
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentUpload
import de.littletrotter.application.quote.port.outbound.AttachQuoteFileRemote
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Application service of `AttachQuoteFileUseCase`; the call itself is the outbound `AttachQuoteFileRemote`. */
@ApplicationScoped
class AttachQuoteFileService @Inject constructor(
    private val remote: AttachQuoteFileRemote,
) : AttachQuoteFileUseCase {

    override fun attach(quoteId: String, upload: QuoteAttachmentUpload): String =
        remote.attach(quoteId, upload)
}
