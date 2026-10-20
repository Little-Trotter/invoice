package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.EditQuoteHeaderCommand
import de.littletrotter.application.quote.port.inbound.EditQuoteHeaderUseCase
import de.littletrotter.application.quote.port.outbound.EditQuoteHeaderRemote
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Application service of `EditQuoteHeaderUseCase`; the call itself is the outbound `EditQuoteHeaderRemote`. */
@ApplicationScoped
class EditQuoteHeaderService @Inject constructor(
    private val remote: EditQuoteHeaderRemote,
) : EditQuoteHeaderUseCase {

    override fun editHeader(command: EditQuoteHeaderCommand) =
        remote.editHeader(command)
}
