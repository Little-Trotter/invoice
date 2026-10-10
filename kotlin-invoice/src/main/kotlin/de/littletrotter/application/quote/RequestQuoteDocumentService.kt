package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.QuoteDocumentMark
import de.littletrotter.application.quote.port.inbound.RequestQuoteDocumentUseCase
import de.littletrotter.application.quote.port.outbound.RequestQuoteDocumentRemote
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Application service of `RequestQuoteDocumentUseCase`; the call itself is the outbound `RequestQuoteDocumentRemote`. */
@ApplicationScoped
class RequestQuoteDocumentService @Inject constructor(
    private val remote: RequestQuoteDocumentRemote,
) : RequestQuoteDocumentUseCase {

    override fun request(quoteId: String, mark: QuoteDocumentMark): String =
        remote.request(quoteId, mark)
}
