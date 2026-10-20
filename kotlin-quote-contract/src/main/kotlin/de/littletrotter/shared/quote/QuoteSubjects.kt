package de.littletrotter.shared.quote

/** NATS subjects of the offer-to-invoice hand-over; the single place the literals are written. */
object QuoteSubjects {
    /** Offer side to invoicing side: an invoice draft is ordered for an offer. */
    const val INVOICE_ORDER_REQUESTED: String = "quote.invoice-order.requested"

    /** Invoicing side to offer side: the invoice draft of an order exists. */
    const val INVOICE_CREATED: String = "invoice.created-from-quote"

    /** Invoicing side to offer side: no invoice draft could be created for an order. */
    const val INVOICE_REJECTED: String = "invoice.creation-from-quote.rejected"

    /** Both answers of the invoicing side. */
    val INVOICE_REPLY: List<String> = listOf(INVOICE_CREATED, INVOICE_REJECTED)
}
