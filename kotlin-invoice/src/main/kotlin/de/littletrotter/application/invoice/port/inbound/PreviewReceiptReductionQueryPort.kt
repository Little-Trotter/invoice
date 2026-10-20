package de.littletrotter.application.invoice.port.inbound

import de.littletrotter.shared.receipt.DocumentSize

/** What reducing an upload would give, computed without storing anything. */
class ReceiptReductionPreview(
    val originalSize: DocumentSize,
    val reducedSize: DocumentSize,
    /** Whether [reducedSize] meets the target size per receipt. */
    val reachesTarget: Boolean,
    /** PNG of the first page of the reduced version; `null` when it cannot be rendered. */
    val firstPagePng: ByteArray?,
)

/** Input port for the upload dialog: the sizes and a first-page preview of the reduced version. */
fun interface PreviewReceiptReductionQueryPort {
    fun previewReduction(pdfData: ByteArray): ReceiptReductionPreview
}
