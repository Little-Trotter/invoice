package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.QuoteInvoiceOrderRequest
import de.littletrotter.application.quote.port.inbound.RequestQuoteInvoiceUseCase
import de.littletrotter.application.quote.port.outbound.RequestQuoteInvoiceRemote
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Application service of `RequestQuoteInvoiceUseCase`; the call itself is the outbound `RequestQuoteInvoiceRemote`. */
@ApplicationScoped
class RequestQuoteInvoiceService @Inject constructor(
    private val remote: RequestQuoteInvoiceRemote,
) : RequestQuoteInvoiceUseCase {

    override fun request(quoteId: String, order: QuoteInvoiceOrderRequest): String = remote.request(quoteId, order)
}
