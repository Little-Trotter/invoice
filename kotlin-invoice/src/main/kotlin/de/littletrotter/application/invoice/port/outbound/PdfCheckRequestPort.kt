package de.littletrotter.application.invoice.port.outbound

/**
 * Asks the conformance service to check a stored PDF. The request is a claim check: only the
 * object key and the digest the bytes must have travel. Implementations append to the
 * transactional outbox, so the call runs inside the transaction that made the document true and
 * a request is never lost after commit.
 */
fun interface PdfCheckRequestPort {

    fun request(order: PdfCheckOrder)
}

/** One document to check: the generation job that produced it and where its PDF is stored. */
data class PdfCheckOrder(
    val jobId: Long,
    val objectKey: String,
    val sha256: String,
    val correlationId: String?,
)
