package de.littletrotter.application.quote.port.outbound

import de.littletrotter.application.quote.port.inbound.QuoteDocumentContent

/** Remote side of `QuoteDocumentFileQueryPort`: the offer component call behind the application service. */
interface QuoteDocumentFileRemote {

    /** The PDF of [documentId]; throws `QuoteNotFoundException` while it is not available. */
    fun pdf(quoteId: String, documentId: String): QuoteDocumentContent

    /** The machine-readable data of [documentId], or `null` when the document carries none. */
    fun xml(quoteId: String, documentId: String): QuoteDocumentContent?
}
