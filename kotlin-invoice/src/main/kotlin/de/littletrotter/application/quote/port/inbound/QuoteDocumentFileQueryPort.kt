package de.littletrotter.application.quote.port.inbound

/** Downloads the files of a rendered offer copy. */
interface QuoteDocumentFileQueryPort {

    /** The PDF of [documentId]; throws `QuoteNotFoundException` while it is not available. */
    fun pdf(quoteId: String, documentId: String): QuoteDocumentContent

    /** The machine-readable data of [documentId], or `null` when the document carries none. */
    fun xml(quoteId: String, documentId: String): QuoteDocumentContent?
}
