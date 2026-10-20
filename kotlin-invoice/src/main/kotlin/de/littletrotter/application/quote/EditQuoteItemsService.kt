package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.EditQuoteItemsUseCase
import de.littletrotter.application.quote.port.inbound.QuoteItemInput
import de.littletrotter.application.quote.port.outbound.EditQuoteItemsRemote
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Application service of `EditQuoteItemsUseCase`; the call itself is the outbound `EditQuoteItemsRemote`. */
@ApplicationScoped
class EditQuoteItemsService @Inject constructor(
    private val remote: EditQuoteItemsRemote,
) : EditQuoteItemsUseCase {

    override fun editItems(quoteId: String, lines: List<QuoteItemInput>) =
        remote.editItems(quoteId, lines)
}
