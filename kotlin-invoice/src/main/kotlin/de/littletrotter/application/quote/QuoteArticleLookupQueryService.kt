package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.QuoteArticleLookupQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteArticleOption
import de.littletrotter.application.quote.port.outbound.QuoteArticleLookupRemote
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Application service of `QuoteArticleLookupQueryPort`; the call itself is the outbound `QuoteArticleLookupRemote`. */
@ApplicationScoped
class QuoteArticleLookupQueryService @Inject constructor(
    private val remote: QuoteArticleLookupRemote,
) : QuoteArticleLookupQueryPort {

    override fun articles(quoteId: String, category: InvoiceItemCategory, text: String, limit: Int): List<QuoteArticleOption> =
        remote.articles(quoteId, category, text, limit)
}
