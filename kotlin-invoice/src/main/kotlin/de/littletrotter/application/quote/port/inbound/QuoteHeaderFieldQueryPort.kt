package de.littletrotter.application.quote.port.inbound

/** The business-term fields the header of an offer offers for entry. */
fun interface QuoteHeaderFieldQueryPort {

    fun headerFields(quoteId: String): List<QuoteHeaderField>
}
