package de.littletrotter.application.quote.port.inbound

/** Reads the offer list kept by the offer component; never cached here. */
fun interface QuoteListQueryPort {

    /** The window [criteria] describes, newest first, with the size of the whole filtered set. */
    fun findPage(criteria: QuoteListCriteria): QuotePage
}
