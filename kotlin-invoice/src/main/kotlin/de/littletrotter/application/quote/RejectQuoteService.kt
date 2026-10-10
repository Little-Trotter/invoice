package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.RejectQuoteUseCase
import de.littletrotter.application.quote.port.outbound.RejectQuoteRemote
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Application service of `RejectQuoteUseCase`; the call itself is the outbound `RejectQuoteRemote`. */
@ApplicationScoped
class RejectQuoteService @Inject constructor(
    private val remote: RejectQuoteRemote,
) : RejectQuoteUseCase {

    override fun reject(quoteId: String, reason: String) =
        remote.reject(quoteId, reason)
}
