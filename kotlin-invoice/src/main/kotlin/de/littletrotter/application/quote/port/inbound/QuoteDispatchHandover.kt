package de.littletrotter.application.quote.port.inbound

/**
 * The mail draft an offer was handed over in. The user completes and sends it on the mail compose
 * page at [composeUrl]; the offer is only marked as sent once the mail component reports the
 * actual send, never by this call.
 */
data class QuoteDispatchHandover(val draftId: String, val composeUrl: String)
