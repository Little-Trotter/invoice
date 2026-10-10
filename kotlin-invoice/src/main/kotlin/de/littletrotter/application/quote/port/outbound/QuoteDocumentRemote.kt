package de.littletrotter.application.quote.port.outbound

import de.littletrotter.application.quote.port.inbound.QuoteDocument

/** Remote side of `QuoteDocumentQueryPort`: the offer component call behind the application service. */
fun interface QuoteDocumentRemote {

    /** Documents of [quoteId]. */
    fun documents(quoteId: String): List<QuoteDocument>
}
