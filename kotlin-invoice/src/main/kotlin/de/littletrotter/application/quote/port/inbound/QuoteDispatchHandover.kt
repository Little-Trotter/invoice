package de.littletrotter.application.quote.port.inbound

/**
 * Where the dispatch of an offer stands after the hand-over. The offer is only marked as sent once
 * the mail component reports the actual send, never by this call.
 */
sealed class QuoteDispatchHandover {

    /** A mail draft exists; the user completes and sends it on the mail compose page at [composeUrl]. */
    data class Composed(val draftId: String, val composeUrl: String) : QuoteDispatchHandover()

    /** The dispatch copy of the offer is still being rendered; the user triggers the send again once it exists. */
    data class DocumentPending(val documentId: String) : QuoteDispatchHandover()
}
