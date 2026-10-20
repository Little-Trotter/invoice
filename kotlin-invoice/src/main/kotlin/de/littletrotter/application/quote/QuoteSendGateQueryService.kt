package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.QuoteSendGateQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteSendGateState
import de.littletrotter.application.quote.port.outbound.QuoteSendGateRemote
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Application service of `QuoteSendGateQueryPort`; the call itself is the outbound `QuoteSendGateRemote`. */
@ApplicationScoped
class QuoteSendGateQueryService @Inject constructor(
    private val remote: QuoteSendGateRemote,
) : QuoteSendGateQueryPort {

    override fun gate(quoteId: String): QuoteSendGateState =
        remote.gate(quoteId)
}
