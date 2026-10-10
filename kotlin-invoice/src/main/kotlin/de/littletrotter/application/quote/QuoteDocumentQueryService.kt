package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.QuoteDocument
import de.littletrotter.application.quote.port.inbound.QuoteDocumentQueryPort
import de.littletrotter.application.quote.port.outbound.QuoteDocumentRemote
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Application service of `QuoteDocumentQueryPort`; the call itself is the outbound `QuoteDocumentRemote`. */
@ApplicationScoped
class QuoteDocumentQueryService @Inject constructor(
    private val remote: QuoteDocumentRemote,
) : QuoteDocumentQueryPort {

    override fun documents(quoteId: String): List<QuoteDocument> =
        remote.documents(quoteId)
}
