package de.littletrotter.application.invoice.port.outbound

import de.littletrotter.domain.masterdata.ReceiptNormalisationStage
import de.littletrotter.shared.receipt.DocumentSize

/**
 * No-op reducer — used only by [de.littletrotter.application.invoice.ReceiptUploadService]'s
 * backwards-compatible secondary constructors, where CDI is not available (same pattern as
 * [de.littletrotter.application.cache.NoopInvoiceCachePort]). Every call reports the input as
 * already meeting the target; a test that actually needs reduction behaviour injects a mock or the
 * real [de.littletrotter.infrastructure.receipt.PdfBoxReceiptPdfReducer] instead.
 */
class NoopReceiptPdfReducer : ReceiptPdfReducer {
    override fun reduce(pdfData: ByteArray, target: DocumentSize): ReceiptReduction =
        ReceiptReduction.Reduced(pdfData, ReceiptNormalisationStage.NONE, dpi = 0)
}
