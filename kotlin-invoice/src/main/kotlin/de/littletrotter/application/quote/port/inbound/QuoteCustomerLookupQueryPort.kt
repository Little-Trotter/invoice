package de.littletrotter.application.quote.port.inbound

/** Customer picker of the create-offer dialog. */
fun interface QuoteCustomerLookupQueryPort {

    /** Customers matching [text] (blank means all), at most [limit]. */
    fun customers(text: String, limit: Int): List<QuoteCustomerOption>
}
