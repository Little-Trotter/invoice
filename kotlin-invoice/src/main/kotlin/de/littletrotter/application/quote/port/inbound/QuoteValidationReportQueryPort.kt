package de.littletrotter.application.quote.port.inbound

/** Reads the check report of a rendered offer copy. */
fun interface QuoteValidationReportQueryPort {

    /** The report of [documentId], or `null` when the document has none. */
    fun report(quoteId: String, documentId: String): QuoteValidationReport?
}
