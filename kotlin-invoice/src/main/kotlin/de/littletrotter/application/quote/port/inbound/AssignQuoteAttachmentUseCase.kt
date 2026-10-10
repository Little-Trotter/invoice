package de.littletrotter.application.quote.port.inbound

import de.littletrotter.application.command.CommandEntryPoint

/** Moves an attachment to a line of its offer or releases it from its line. */
interface AssignQuoteAttachmentUseCase : CommandEntryPoint {

    fun assign(command: AssignQuoteAttachmentCommand)
}
