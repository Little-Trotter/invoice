package de.littletrotter.application.quote.port.outbound

import de.littletrotter.application.quote.port.inbound.QuoteCustomerOption

/** Remote side of `QuoteCustomerLookupQueryPort`: the offer component call behind the application service. */
fun interface QuoteCustomerLookupRemote {

    /** Customers matching [text] (blank means all), at most [limit]. */
    fun customers(text: String, limit: Int): List<QuoteCustomerOption>
}
