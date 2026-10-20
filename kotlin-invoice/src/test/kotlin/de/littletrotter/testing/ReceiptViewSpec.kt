package de.littletrotter.testing

import de.littletrotter.application.invoice.port.inbound.ReceiptView
import de.littletrotter.domain.masterdata.ReceiptNormalisationStage
import java.time.LocalDate
import java.time.OffsetDateTime

/** Mutable scratch builder for a [ReceiptView] with the same defaults a new pool receipt has. */
class ReceiptViewSpec {
    var id: Long? = null
    var fileName: String = ""
    var description: String = ""
    var objectKey: String = ""
    var sizeBytes: Long? = null
    var uploadedAt: OffsetDateTime? = null
    var documentDate: LocalDate? = null
    var normalisationStage: ReceiptNormalisationStage = ReceiptNormalisationStage.NONE
    var originalSizeBytes: Long? = null

    fun build(): ReceiptView = ReceiptView(
        id = id,
        fileName = fileName,
        description = description,
        objectKey = objectKey,
        sizeBytes = sizeBytes,
        uploadedAt = uploadedAt,
        documentDate = documentDate,
        normalisationStage = normalisationStage,
        originalSizeBytes = originalSizeBytes,
    )
}

fun receiptView(init: ReceiptViewSpec.() -> Unit = {}): ReceiptView =
    ReceiptViewSpec().apply(init).build()

/** Copy of this view with [init] applied to its fields. */
fun ReceiptView.edit(init: ReceiptViewSpec.() -> Unit): ReceiptView {
    val view = this
    return ReceiptViewSpec().apply {
        id = view.id
        fileName = view.fileName
        description = view.description
        objectKey = view.objectKey
        sizeBytes = view.sizeBytes
        uploadedAt = view.uploadedAt
        documentDate = view.documentDate
        normalisationStage = view.normalisationStage
        originalSizeBytes = view.originalSizeBytes
        init()
    }.build()
}
