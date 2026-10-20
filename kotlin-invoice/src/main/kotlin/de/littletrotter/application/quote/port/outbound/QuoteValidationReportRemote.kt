package de.littletrotter.application.quote.port.outbound

import de.littletrotter.application.quote.port.inbound.QuoteValidationReport

/** Remote side of `QuoteValidationReportQueryPort`: the offer component call behind the application service. */
fun interface QuoteValidationReportRemote {

    /** The report of [documentId], or `null` when the document has none. */
    fun report(quoteId: String, documentId: String): QuoteValidationReport?
}
