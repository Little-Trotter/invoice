package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.ReturnQuoteToDraftUseCase
import de.littletrotter.application.quote.port.outbound.ReturnQuoteToDraftRemote
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Application service of `ReturnQuoteToDraftUseCase`; the call itself is the outbound `ReturnQuoteToDraftRemote`. */
@ApplicationScoped
class ReturnQuoteToDraftService @Inject constructor(
    private val remote: ReturnQuoteToDraftRemote,
) : ReturnQuoteToDraftUseCase {

    override fun returnToDraft(quoteId: String, note: String) =
        remote.returnToDraft(quoteId, note)
}
