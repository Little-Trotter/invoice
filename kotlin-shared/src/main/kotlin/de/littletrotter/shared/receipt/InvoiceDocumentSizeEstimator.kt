package de.littletrotter.shared.receipt

/**
 * The seam this subdomain exists to isolate: *how large will the produced e-invoice document
 * become, given this receipt set?* [InvoiceDocumentBudget] depends only on this interface, so the
 * estimator ([CombinedDocumentSizeEstimator]) can be replaced by a more precise one without touching [InvoiceDocumentBudget],
 * `kotlin-invoice`'s guards or the UI's hints.
 *
 * `distinctReceipts` is expected to already be deduplicated by object key — callers that go
 * through [InvoiceDocumentBudget] get this for free.
 */
fun interface InvoiceDocumentSizeEstimator {
    fun estimate(distinctReceipts: Collection<ReceiptSize>): DocumentSize
}

/**
 * `reserve + per receipt (Base64 text in the XML + its pages in the PDF)`.
 *
 * A receipt travels twice in the produced document: XRechnung's BG-24 `AttachmentBinaryObject`
 * carries it as uncompressed Base64 text inside the invoice XML (raw bytes x 4/3, rounded up to
 * 4-byte blocks), and the PDF shows its pages as JPEG images. What the pages weigh depends on the
 * page content, not on the stored bytes, so it is the value measured at upload
 * ([ReceiptPageProfile.annexBytes]). A receipt without a profile is assumed to weigh as much as
 * its bytes times [rasterFactor]. The reserve covers the invoice body, the PDF structure and the
 * header bands. A receipt that renders larger than measured is what the export backstop in
 * `kotlin-xpdf` is for.
 */
class CombinedDocumentSizeEstimator(
    private val reserve: DocumentSize = ReceiptSizePolicy.INVOICE_DOCUMENT_RESERVE,
    private val rasterFactor: Int = 1,
) : InvoiceDocumentSizeEstimator {

    init {
        require(rasterFactor >= 0) { "The raster factor must not be negative, was $rasterFactor." }
    }

    override fun estimate(distinctReceipts: Collection<ReceiptSize>): DocumentSize =
        reserve + DocumentSize.sum(distinctReceipts.map { it.size.base64Encoded() + pagesSize(it) })

    private fun pagesSize(receipt: ReceiptSize): DocumentSize =
        receipt.profile?.let { DocumentSize.ofBytes(it.annexBytes) } ?: (receipt.size * rasterFactor)
}
