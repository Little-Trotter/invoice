package de.littletrotter.application.invoice.port.outbound

import de.littletrotter.shared.receipt.ReceiptPageProfile

/**
 * Outbound port reading the page facts of a receipt PDF, including what its pages will weigh in the print annex. Called once per
 * stored version; the result is persisted with the receipt so print and budget never open the
 * file just to learn what it is.
 */
fun interface ReceiptPageInspector {
    /** The profile of [pdfData]; [ReceiptPageProfile.unmeasured] when the file cannot be analysed. */
    fun inspect(pdfData: ByteArray): ReceiptPageProfile
}

/** Used by the backwards-compatible constructors of services in tests without PDF handling. */
class NoopReceiptPageInspector : ReceiptPageInspector {
    override fun inspect(pdfData: ByteArray): ReceiptPageProfile = ReceiptPageProfile.unmeasured(pdfData.size.toLong())
}

/**
 * Outbound port rendering a small preview image of a receipt's first page for the upload dialog.
 * Nothing is stored; the preview is only shown to the user who decides between versions.
 */
fun interface ReceiptPagePreview {
    /** PNG bytes of the first page, or `null` when the page cannot be rendered. */
    fun firstPagePng(pdfData: ByteArray): ByteArray?
}
