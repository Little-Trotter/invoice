package de.littletrotter.shared.receipt

/**
 * How a stored receipt is turned into annex pages at print time. [IMAGE_PDF]: every page is a
 * single JPEG at or below the print resolution, so the pages are imported without rendering.
 * [DOCUMENT]: anything else; pages are rendered one at a time at the print raster.
 */
enum class ReceiptRasterKind { IMAGE_PDF, DOCUMENT }

/**
 * Facts about a receipt PDF recorded at upload. [a4Factor] is the sum over all pages of the
 * displayed page area divided by the A4 page area (a one-page A4 receipt is 1.0, a receipt-roll
 * slip a fraction of it). [annexBytes] is what the receipt's pages weigh in the printed annex: the
 * receipt's own bytes for an image receipt, the JPEG total of the pages rendered at the print
 * raster for a document. It is measured once because it depends on the page content, not on the
 * stored bytes.
 */
data class ReceiptPageProfile(
    val pageCount: Int,
    val a4Factor: Double,
    val rasterKind: ReceiptRasterKind,
    val annexBytes: Long,
) {
    init {
        require(pageCount >= 1) { "A receipt has at least one page, was $pageCount." }
        require(a4Factor.isFinite() && a4Factor > 0.0) { "The A4 factor must be positive, was $a4Factor." }
        require(annexBytes > 0) { "The annex size must be positive, was $annexBytes." }
    }

    companion object {
        /**
         * Stand-in for a receipt that cannot be analysed (the print will not be able to load it
         * either): one A4 page whose pages weigh as much as the receipt's bytes.
         */
        fun unmeasured(receiptBytes: Long) = ReceiptPageProfile(
            pageCount = 1,
            a4Factor = 1.0,
            rasterKind = ReceiptRasterKind.DOCUMENT,
            annexBytes = maxOf(receiptBytes, 1L),
        )
    }
}
