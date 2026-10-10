package de.littletrotter.application.quote.port.outbound

import de.littletrotter.application.quote.port.inbound.EditQuoteHeaderCommand

/** Remote side of `EditQuoteHeaderUseCase`: the offer component call behind the application service. */
fun interface EditQuoteHeaderRemote {

    /** Replaces the header fields of the draft; refused by the component once the offer left draft. */
    fun editHeader(command: EditQuoteHeaderCommand)
}
