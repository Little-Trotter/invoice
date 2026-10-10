package de.littletrotter.application.quote.port.inbound

/** Reads the history of one offer. */
fun interface QuoteLogQueryPort {

    /** Log entries of [quoteId]; [kinds] restricts them, an empty set means all kinds. */
    fun log(quoteId: String, kinds: Set<QuoteLogKind>): List<QuoteLogEntry>
}
