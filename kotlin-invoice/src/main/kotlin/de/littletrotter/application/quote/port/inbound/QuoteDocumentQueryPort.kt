package de.littletrotter.application.quote.port.inbound

/** Reads the rendered copies of an offer. */
fun interface QuoteDocumentQueryPort {

    /** Documents of [quoteId], oldest first as the component lists them. */
    fun documents(quoteId: String): List<QuoteDocument>
}
