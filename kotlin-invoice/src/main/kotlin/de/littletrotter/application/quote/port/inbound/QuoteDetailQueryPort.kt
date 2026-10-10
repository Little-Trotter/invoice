package de.littletrotter.application.quote.port.inbound

/** Reads one offer completely. */
fun interface QuoteDetailQueryPort {

    /** The offer [quoteId]; throws `QuoteNotFoundException` when it does not exist (any more). */
    fun find(quoteId: String): QuoteDetail
}
