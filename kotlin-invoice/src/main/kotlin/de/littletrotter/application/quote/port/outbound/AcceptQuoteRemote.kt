package de.littletrotter.application.quote.port.outbound

import java.time.LocalDate

/** Remote side of `AcceptQuoteUseCase`: the offer component call behind the application service. */
fun interface AcceptQuoteRemote {

    /** The customer accepted [quoteId] on [acceptedOn], optionally quoting their own order reference. */
    fun accept(quoteId: String, acceptedOn: LocalDate, customerOrderReference: String?)
}
