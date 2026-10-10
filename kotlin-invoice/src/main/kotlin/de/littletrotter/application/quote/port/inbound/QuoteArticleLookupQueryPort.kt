package de.littletrotter.application.quote.port.inbound

import de.littletrotter.shared.catalogue.InvoiceItemCategory

/** Article picker of the offer line editor; prices are those valid for the offer's customer. */
fun interface QuoteArticleLookupQueryPort {

    /** Articles of the kind [category] matching [text] (blank means all) for the offer [quoteId], at most [limit]. */
    fun articles(quoteId: String, category: InvoiceItemCategory, text: String, limit: Int): List<QuoteArticleOption>
}
