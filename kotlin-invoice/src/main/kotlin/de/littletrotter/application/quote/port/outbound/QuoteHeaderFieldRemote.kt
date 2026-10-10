package de.littletrotter.application.quote.port.outbound

import de.littletrotter.application.quote.port.inbound.QuoteHeaderField

/** Remote side of `QuoteHeaderFieldQueryPort`: the offer component call behind the application service. */
fun interface QuoteHeaderFieldRemote {

    fun headerFields(quoteId: String): List<QuoteHeaderField>
}
