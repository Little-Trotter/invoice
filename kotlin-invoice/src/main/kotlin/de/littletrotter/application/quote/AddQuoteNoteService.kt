package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.AddQuoteNoteUseCase
import de.littletrotter.application.quote.port.outbound.AddQuoteNoteRemote
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Application service of `AddQuoteNoteUseCase`; the call itself is the outbound `AddQuoteNoteRemote`. */
@ApplicationScoped
class AddQuoteNoteService @Inject constructor(
    private val remote: AddQuoteNoteRemote,
) : AddQuoteNoteUseCase {

    override fun addNote(quoteId: String, text: String) =
        remote.addNote(quoteId, text)
}
