package de.littletrotter.application.quote.port.outbound

import de.littletrotter.application.quote.port.inbound.QuoteArticleOption
import de.littletrotter.shared.catalogue.InvoiceItemCategory

/** Remote side of `QuoteArticleLookupQueryPort`: the offer component call behind the application service. */
fun interface QuoteArticleLookupRemote {

    /** Articles of the kind [category] matching [text] (blank means all) for the offer [quoteId], at most [limit]. */
    fun articles(quoteId: String, category: InvoiceItemCategory, text: String, limit: Int): List<QuoteArticleOption>
}
