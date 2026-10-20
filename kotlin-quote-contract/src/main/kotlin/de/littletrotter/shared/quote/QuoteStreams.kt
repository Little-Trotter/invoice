package de.littletrotter.shared.quote

/**
 * JetStream stream names of the offer-to-invoice hand-over. [INVOICE_ORDER] is created by the offer
 * side that publishes into it, [INVOICE_REPLY] by the invoicing side that answers on it.
 */
object QuoteStreams {
    const val INVOICE_ORDER: String = "LITTLE_TROTTER_QUOTE_INVOICE_ORDER"
    const val INVOICE_REPLY: String = "LITTLE_TROTTER_QUOTE_INVOICE"
}
