package de.littletrotter.application.quote.port.outbound

/** Remote side of `CreateQuoteUseCase`: the offer component call behind the application service. */
fun interface CreateQuoteRemote {

    /** Creates a draft for [customerId] and returns the new offer's id. */
    fun create(customerId: String): String
}
