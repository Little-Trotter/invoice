package de.littletrotter.application.invoice.port.outbound

/**
 * Read side of the archived invoice artifacts: the callers that only hold an object key or a
 * document id (download verification, send-block journal) need the stored checksum, never the
 * document row itself.
 */
interface InvoiceDocumentReadRepository {

    /** SHA-256 recorded for the artifact stored under [objectKey]; `null` when no row tracks that
     *  key (legacy job, receipt) or the row has no checksum yet. */
    fun findDocumentChecksum(objectKey: String): String?

    /** SHA-256 recorded for the document row [documentId]; `null` when it is unknown or unchecked. */
    fun findDocumentChecksumById(documentId: Long): String?
}
