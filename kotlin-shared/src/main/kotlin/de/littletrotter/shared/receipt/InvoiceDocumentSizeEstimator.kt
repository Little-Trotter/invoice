package de.littletrotter.shared.receipt

/**
 * The seam this subdomain exists to isolate: *how large will the produced e-invoice document
 * become, given this receipt set?* [InvoiceDocumentBudget] depends only on this interface, so the
 * estimator ([Base64WithReserveEstimator]) can be replaced later by one that models the
 * rasterized-page annex and the PDF/A-3 embedding without touching [InvoiceDocumentBudget],
 * `kotlin-invoice`'s guards or the UI's hints.
 *
 * `distinctReceipts` is expected to already be deduplicated by object key — callers that go
 * through [InvoiceDocumentBudget] get this for free.
 */
fun interface InvoiceDocumentSizeEstimator {
    fun estimate(distinctReceipts: Collection<ReceiptSize>): DocumentSize
}

/**
 * `reserve + sum of Base64-encoded receipt sizes` (PO decision, IR-1).
 *
 * The budget counts result bytes on the strictest channel a receipt travels over: XRechnung's
 * BG-24 `AttachmentBinaryObject` transports it as uncompressed Base64 text inside the invoice XML,
 * which inflates the raw bytes by 4/3 (rounded up to 4-byte blocks). Estimating on raw bytes would
 * understate the produced document by that factor. The reserve on top covers the invoice body
 * itself, the surrounding XML text and embedded fonts.
 *
 * **What this deliberately does not model:** a receipt is also embedded a second time, as
 * rasterized JPEG pages (200 dpi) appended for human-readable viewers. The real artifact is
 * measurably larger than this estimate — a known, accepted simplification, not a promise about the
 * file size on disk; the export backstop in `kotlin-xpdf` stays in place as a safety net for
 * exactly this reason.
 */
class Base64WithReserveEstimator(
    private val reserve: DocumentSize = ReceiptSizePolicy.INVOICE_DOCUMENT_RESERVE,
) : InvoiceDocumentSizeEstimator {

    override fun estimate(distinctReceipts: Collection<ReceiptSize>): DocumentSize =
        reserve + DocumentSize.sum(distinctReceipts.map { it.size.base64Encoded() })
}
