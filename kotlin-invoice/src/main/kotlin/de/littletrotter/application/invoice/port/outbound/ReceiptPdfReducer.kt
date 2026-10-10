package de.littletrotter.application.invoice.port.outbound

import de.littletrotter.domain.masterdata.ReceiptNormalisationStage
import de.littletrotter.shared.receipt.DocumentSize

/**
 * Outbound port for reducing a receipt PDF towards a target size (`ReceiptSizePolicy
 * .TARGET_RECEIPT_SIZE` in production). [de.littletrotter.infrastructure.receipt
 * .PdfBoxReceiptPdfReducer] is the only real adapter; the application layer depends on this
 * interface so `ReceiptUploadService`/`NormaliseReceipt` never touch PDFBox directly.
 */
fun interface ReceiptPdfReducer {
    /**
     * Reduces [pdfData] towards [target], trying [ReceiptNormalisationStage]'s stages in order
     * and stopping as soon as the result meets [target]. Never mutates [pdfData].
     */
    fun reduce(pdfData: ByteArray, target: DocumentSize): ReceiptReduction
}

/** Outcome of [ReceiptPdfReducer.reduce]. */
sealed interface ReceiptReduction {

    /**
     * A result at or below the requested target (or, when [stage] is [ReceiptNormalisationStage
     * .NONE], the untouched input — the reducer's contract is that it never returns bytes larger
     * than [pdfData]). [dpi] is the DPI actually used to produce [bytes]; 0 when [stage] is
     * [ReceiptNormalisationStage.NONE] (no reduction ran).
     */
    data class Reduced(
        val bytes: ByteArray,
        val stage: ReceiptNormalisationStage,
        val dpi: Int,
    ) : ReceiptReduction {
        // Content-based equality for the byte payload — a data class would otherwise compare the
        // ByteArray by identity (detekt ArrayInDataClass), same pattern as kotlin-xpdf's
        // BasePdfStage.BaseResult.
        override fun equals(other: Any?): Boolean =
            other is Reduced && bytes.contentEquals(other.bytes) && stage == other.stage && dpi == other.dpi

        override fun hashCode(): Int = 31 * (31 * bytes.contentHashCode() + stage.hashCode()) + dpi
    }

    /** Every stage was tried and the result still exceeds the target. [pageCount] and
     *  [resultingSize] let the caller phrase a precise rejection. */
    data class StillTooLarge(val pageCount: Int, val resultingSize: DocumentSize) : ReceiptReduction
}
