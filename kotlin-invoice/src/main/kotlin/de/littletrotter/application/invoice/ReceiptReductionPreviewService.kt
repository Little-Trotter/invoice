package de.littletrotter.application.invoice

import de.littletrotter.application.invoice.port.inbound.PreviewReceiptReductionQueryPort
import de.littletrotter.application.invoice.port.inbound.ReceiptReductionPreview
import de.littletrotter.application.invoice.port.outbound.ReceiptPagePreview
import de.littletrotter.application.invoice.port.outbound.ReceiptPdfReducer
import de.littletrotter.application.invoice.port.outbound.ReceiptReduction
import de.littletrotter.shared.receipt.DocumentSize
import de.littletrotter.shared.receipt.ReceiptSizePolicy
import de.littletrotter.shared.receipt.meetsTarget
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Runs the same reduction the upload would run, but keeps nothing: the dialog shows the user what
 * each version weighs and looks like before the one stored version is chosen.
 */
@ApplicationScoped
class ReceiptReductionPreviewService @Inject constructor(
    private val receiptPdfReducer: ReceiptPdfReducer,
    private val pagePreview: ReceiptPagePreview,
) : PreviewReceiptReductionQueryPort {

    override fun previewReduction(pdfData: ByteArray): ReceiptReductionPreview {
        val bytes = when (val reduction = receiptPdfReducer.reduce(pdfData, ReceiptSizePolicy.TARGET_RECEIPT_SIZE)) {
            is ReceiptReduction.Reduced -> reduction.bytes
            is ReceiptReduction.StillTooLarge -> reduction.bytes
        }
        val reducedSize = DocumentSize.ofBytes(bytes.size.toLong())
        return ReceiptReductionPreview(
            originalSize = DocumentSize.ofBytes(pdfData.size.toLong()),
            reducedSize = reducedSize,
            reachesTarget = ReceiptSizePolicy.meetsTarget(reducedSize),
            firstPagePng = pagePreview.firstPagePng(bytes),
        )
    }
}
