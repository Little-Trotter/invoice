package de.littletrotter.application.quote.port.inbound

import de.littletrotter.application.command.CommandEntryPoint

/** Sends an offer under review back to draft. */
interface ReturnQuoteToDraftUseCase : CommandEntryPoint {

    /** Returns [quoteId] to draft; [note] tells the author what to change. */
    fun returnToDraft(quoteId: String, note: String)
}
