package de.littletrotter.application.invoice.port.inbound

import java.time.OffsetDateTime

/** Read side of the document of record: which object it is and whether it is a reproduction. */
fun interface DocumentReproductionQueryPort {
    fun currentDocument(invoiceId: Long): CurrentDocumentView?
}

/** The invoice's document of record as the UI needs it for the reproduction tag and the send gate. */
data class CurrentDocumentView(
    val objectKey: String,
    val jobId: Long?,
    val reproduced: Boolean,
    val reproducedAt: OffsetDateTime?,
    val reproductionOfSha256: String?,
)
