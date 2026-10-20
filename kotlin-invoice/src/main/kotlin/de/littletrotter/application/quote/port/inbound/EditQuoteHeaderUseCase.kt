package de.littletrotter.application.quote.port.inbound

import de.littletrotter.application.command.CommandEntryPoint

/** Changes the header of a draft offer. */
interface EditQuoteHeaderUseCase : CommandEntryPoint {

    /** Replaces the header fields of the draft; refused by the component once the offer left draft. */
    fun editHeader(command: EditQuoteHeaderCommand)
}
