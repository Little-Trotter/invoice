package de.littletrotter.domain.masterdata

import de.littletrotter.shared.receipt.ReceiptPageProfile
import java.time.LocalDate
import java.time.OffsetDateTime

/** Everything a freshly uploaded [Receipt] row is stored with; see [Receipt.create]. */
data class ReceiptUpload(
    val fileName: String,
    val description: String,
    val objectKey: String,
    val documentDate: LocalDate?,
    val sizeBytes: Long,
    val sha256: String,
    val uploadedAt: OffsetDateTime,
    val normalisationStage: ReceiptNormalisationStage,
    val originalSizeBytes: Long?,
    val profile: ReceiptPageProfile = ReceiptPageProfile.unmeasured(sizeBytes),
)
