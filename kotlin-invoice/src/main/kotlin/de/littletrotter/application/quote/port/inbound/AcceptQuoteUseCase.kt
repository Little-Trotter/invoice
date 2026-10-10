package de.littletrotter.application.quote.port.inbound

import de.littletrotter.application.command.CommandEntryPoint
import java.time.LocalDate

/** Records the customer acceptance of a sent offer. */
interface AcceptQuoteUseCase : CommandEntryPoint {

    /** The customer accepted [quoteId] on [acceptedOn], optionally quoting their own order reference. */
    fun accept(quoteId: String, acceptedOn: LocalDate, customerOrderReference: String?)
}
