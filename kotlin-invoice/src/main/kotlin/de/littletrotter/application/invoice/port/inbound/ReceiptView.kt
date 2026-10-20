package de.littletrotter.application.invoice.port.inbound

import de.littletrotter.domain.masterdata.ReceiptNormalisationStage
import java.time.LocalDate
import java.time.OffsetDateTime

/** Read model of one pool receipt: the columns the receipt pool shows plus the stored object key. */
data class ReceiptView(
    val id: Long?,
    val fileName: String,
    val description: String,
    val objectKey: String,
    val sizeBytes: Long?,
    val uploadedAt: OffsetDateTime?,
    val documentDate: LocalDate?,
    val normalisationStage: ReceiptNormalisationStage,
    val originalSizeBytes: Long?,
)
