package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.QuoteDispatchHandover
import de.littletrotter.application.quote.port.inbound.SendQuoteUseCase
import de.littletrotter.application.quote.port.outbound.SendQuoteRemote
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Application service of `SendQuoteUseCase`; the call itself is the outbound `SendQuoteRemote`. */
@ApplicationScoped
class SendQuoteService @Inject constructor(
    private val remote: SendQuoteRemote,
) : SendQuoteUseCase {

    override fun send(quoteId: String): QuoteDispatchHandover =
        remote.send(quoteId)
}
