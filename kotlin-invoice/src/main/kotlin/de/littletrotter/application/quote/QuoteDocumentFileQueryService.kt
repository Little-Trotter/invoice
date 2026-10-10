package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.QuoteDocumentContent
import de.littletrotter.application.quote.port.inbound.QuoteDocumentFileQueryPort
import de.littletrotter.application.quote.port.outbound.QuoteDocumentFileRemote
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Application service of `QuoteDocumentFileQueryPort`; the call itself is the outbound `QuoteDocumentFileRemote`. */
@ApplicationScoped
class QuoteDocumentFileQueryService @Inject constructor(
    private val remote: QuoteDocumentFileRemote,
) : QuoteDocumentFileQueryPort {

    override fun pdf(quoteId: String, documentId: String): QuoteDocumentContent =
        remote.pdf(quoteId, documentId)

    override fun xml(quoteId: String, documentId: String): QuoteDocumentContent? =
        remote.xml(quoteId, documentId)
}
