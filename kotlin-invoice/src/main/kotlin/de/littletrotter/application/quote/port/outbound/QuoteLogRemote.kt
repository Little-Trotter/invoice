package de.littletrotter.application.quote.port.outbound

import de.littletrotter.application.quote.port.inbound.QuoteLogEntry
import de.littletrotter.application.quote.port.inbound.QuoteLogKind

/** Remote side of `QuoteLogQueryPort`: the offer component call behind the application service. */
fun interface QuoteLogRemote {

    /** Log entries of [quoteId]; [kinds] restricts them, an empty set means all kinds. */
    fun log(quoteId: String, kinds: Set<QuoteLogKind>): List<QuoteLogEntry>
}
