package de.littletrotter.application.invoice.port.inbound

/**
 * Input port for the upload-time readability check: reports the pages of a picked receipt that
 * will probably be hard to read in print because the scan is coarse or blurred. Read-only; the
 * result is a hint for the user, never a reason to refuse the upload.
 */
fun interface ReceiptReadabilityQueryPort {
    /**
     * Pages of [pdfData] that look hard to read; empty for a readable, unreadable-as-PDF or text-only document.
     *
     * @throws ReceiptReadabilityCheckException when the measurement itself failed, e.g. on an undecodable image
     */
    fun findHardToReadPages(pdfData: ByteArray): List<HardToReadPage>
}

/**
 * One page the check reported. [effectiveDpi] is null when only blur was found; [previewPng] is a
 * small picture of the page for the confirmation dialog. A plain class: [previewPng] is an array.
 */
class HardToReadPage(val pageNumber: Int, val effectiveDpi: Int?, val previewPng: ByteArray)

/** The readability measurement failed; callers treat the check as optional and carry on with the upload. */
class ReceiptReadabilityCheckException(message: String, cause: Throwable) : RuntimeException(message, cause)
