package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.CancelQuoteUseCase
import de.littletrotter.application.quote.port.outbound.CancelQuoteRemote
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Application service of `CancelQuoteUseCase`; the call itself is the outbound `CancelQuoteRemote`. */
@ApplicationScoped
class CancelQuoteService @Inject constructor(
    private val remote: CancelQuoteRemote,
) : CancelQuoteUseCase {

    override fun cancel(quoteId: String, reason: String) =
        remote.cancel(quoteId, reason)
}
