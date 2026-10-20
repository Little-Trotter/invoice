package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.Component
import de.littletrotter.application.invoice.ReceiptUploadMode
import de.littletrotter.application.invoice.port.inbound.ReceiptReadabilityQueryPort
import de.littletrotter.application.quote.port.inbound.StoredQuoteReduction
import de.littletrotter.shared.receipt.DocumentSize
import de.littletrotter.shared.receipt.ReceiptSizePolicy
import de.littletrotter.shared.receipt.ReceiptUploadDecision
import de.littletrotter.shared.receipt.uploadDecision
import de.littletrotter.ui.notification.UiNotifications
import de.littletrotter.ui.view.invoice.ReceiptUploadOutcome
import de.littletrotter.ui.view.invoice.offerReadabilityHint
import de.littletrotter.ui.view.invoice.offerReceiptUpload
import de.littletrotter.ui.view.invoice.showReceiptUploadResult

private const val PDF_MIME_TYPE = "application/pdf"

/** A file the user picked for upload, still in memory. */
internal class PickedFile(val mimeType: String, val bytes: ByteArray)

/**
 * Decides at upload time whether a large PDF is reduced, exactly as for invoice receipts: a PDF
 * with hard-to-read pages first gets the readability hint (rescan ends the upload here, accept
 * continues), then the receipt setting reduces without asking, otherwise the receipt question dialog shows both sizes
 * and a preview. The answer travels with the upload, so the offer component stores one version
 * only. A file that is no PDF or already fits the target is stored as sent and never asks; a file
 * above the upload cap is refused by the receipt rule without reaching the callback.
 */
internal fun decideQuoteUpload(
    component: Component?,
    readability: ReceiptReadabilityQueryPort,
    picked: PickedFile,
    onDecided: (reduce: Boolean) -> Unit,
) {
    val bytes = picked.bytes
    if (!picked.mimeType.equals(PDF_MIME_TYPE, ignoreCase = true)) {
        onDecided(false)
        return
    }
    offerReadabilityHint(component, bytes, { decideQuoteSize(component, bytes, onDecided) }, readability)
}

private fun decideQuoteSize(component: Component?, bytes: ByteArray, onDecided: (reduce: Boolean) -> Unit) {
    if (ReceiptSizePolicy.uploadDecision(DocumentSize.ofBytes(bytes.size.toLong())) == ReceiptUploadDecision.ACCEPT_AS_IS) {
        onDecided(false)
        return
    }
    offerReceiptUpload(component, bytes) { mode -> onDecided(mode == ReceiptUploadMode.REDUCE) }
}

/** What a finished upload came to: the size sent and the offer component's report of the reduction it was asked for (null when none was requested or reported). */
internal class QuoteUploadResult(val fileName: String, val uploadedBytes: Int, val reduction: StoredQuoteReduction?)

/**
 * Reports a finished upload exactly like a receipt: "before to after", target missed and the
 * lost-text-layer hint of a rasterised result when a reduction ran for a file above the target
 * size, the plain upload message [plain] otherwise.
 */
internal fun showQuoteUploadResult(component: Component?, result: QuoteUploadResult, plain: String) {
    val reduction = result.reduction
    if (reduction == null || result.uploadedBytes <= ReceiptSizePolicy.TARGET_RECEIPT_SIZE_BYTES) {
        UiNotifications.success(plain)
        return
    }
    showReceiptUploadResult(
        component,
        ReceiptUploadOutcome(result.fileName, reduction.stage, result.uploadedBytes.toLong(), reduction.storedBytes, reductionRequested = true),
    )
}
