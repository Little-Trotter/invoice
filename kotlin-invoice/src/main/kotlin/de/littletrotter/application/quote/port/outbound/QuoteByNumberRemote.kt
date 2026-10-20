package de.littletrotter.application.quote.port.outbound

import de.littletrotter.application.quote.port.inbound.QuoteDetail

/** Remote side of `QuoteByNumberQueryPort`: the offer component call behind the application service. */
fun interface QuoteByNumberRemote {

    /** The offer numbered [number]; throws `QuoteNotFoundException` when no offer carries it. */
    fun find(number: String): QuoteDetail
}
