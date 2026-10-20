package de.littletrotter.application.quote

import de.littletrotter.application.quote.port.inbound.AssignQuoteAttachmentCommand
import de.littletrotter.application.quote.port.inbound.AssignQuoteAttachmentUseCase
import de.littletrotter.application.quote.port.outbound.AssignQuoteAttachmentRemote
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Application service of `AssignQuoteAttachmentUseCase`; the call itself is the outbound `AssignQuoteAttachmentRemote`. */
@ApplicationScoped
class AssignQuoteAttachmentService @Inject constructor(
    private val remote: AssignQuoteAttachmentRemote,
) : AssignQuoteAttachmentUseCase {

    override fun assign(command: AssignQuoteAttachmentCommand) = remote.assign(command)
}
