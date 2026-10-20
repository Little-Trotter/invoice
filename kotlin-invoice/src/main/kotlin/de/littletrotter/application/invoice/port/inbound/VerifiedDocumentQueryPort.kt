package de.littletrotter.application.invoice.port.inbound

import de.littletrotter.shared.DocumentStore
import de.littletrotter.application.invoice.DocumentChecksumMismatchException

/** Input port for handing stored document bytes to a user only after they match the checksum recorded at generation. */
fun interface VerifiedDocumentQueryPort {
    /**
     * Fetches [objectKey] from [documentStore] and verifies it against the persisted checksum
     * before returning the bytes. [invoiceId] and [invoiceNumber] only label the message recorded
     * on a mismatch.
     *
     * @throws DocumentChecksumMismatchException when the stored checksum does not match the bytes.
     */
    fun verifiedGet(documentStore: DocumentStore, objectKey: String, invoiceId: Long?, invoiceNumber: String?): ByteArray
}
