package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.QuoteLogEntry
import de.littletrotter.application.quote.port.inbound.QuoteLogKind
import de.littletrotter.application.quote.port.inbound.QuoteLogQueryPort
import de.littletrotter.application.quote.port.outbound.QuoteLogRemote
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Application service of `QuoteLogQueryPort`; the call itself is the outbound `QuoteLogRemote`. */
@ApplicationScoped
class QuoteLogQueryService @Inject constructor(
    private val remote: QuoteLogRemote,
) : QuoteLogQueryPort {

    override fun log(quoteId: String, kinds: Set<QuoteLogKind>): List<QuoteLogEntry> =
        remote.log(quoteId, kinds)
}
