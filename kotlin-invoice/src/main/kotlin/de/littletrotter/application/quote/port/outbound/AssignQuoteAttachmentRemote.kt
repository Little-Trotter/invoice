package de.littletrotter.application.quote.port.outbound

import de.littletrotter.application.quote.port.inbound.AssignQuoteAttachmentCommand

/** Remote side of `AssignQuoteAttachmentUseCase`: the offer component call behind the application service. */
fun interface AssignQuoteAttachmentRemote {

    fun assign(command: AssignQuoteAttachmentCommand)
}
