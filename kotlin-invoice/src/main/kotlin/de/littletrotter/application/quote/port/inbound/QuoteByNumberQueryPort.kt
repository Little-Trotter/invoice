package de.littletrotter.application.quote.port.inbound

/** Reads one offer by the number a clerk sees and types, since the technical id never appears on screen. */
fun interface QuoteByNumberQueryPort {

    /** The offer numbered [number]; throws `QuoteNotFoundException` when no offer carries it. */
    fun find(number: String): QuoteDetail
}
