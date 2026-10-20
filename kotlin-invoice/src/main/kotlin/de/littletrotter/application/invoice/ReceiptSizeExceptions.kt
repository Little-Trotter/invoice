package de.littletrotter.application.invoice

import de.littletrotter.application.error.ApplicationValidationException
import de.littletrotter.shared.receipt.DocumentSize

/** A raw upload above `ReceiptSizePolicy.MAX_UPLOAD_SIZE` — too large for reduction to help at
 *  all, refused regardless of [ReceiptUploadMode]. */
class ReceiptUploadTooLargeException(val uploaded: DocumentSize, val limit: DocumentSize) :
    ApplicationValidationException(
        "Receipt of ${uploaded.mebiBytes.toInt()} MiB exceeds the maximum upload size of ${limit.mebiBytes.toInt()} MiB.",
    )

/** [ReceiptUploadMode.KEEP_UNREDUCED] above `ReceiptSizePolicy.MAX_UNREDUCED_RECEIPT_SIZE` —
 *  the caller declined reduction and the raw bytes still exceed the smaller unreduced cap. */
class ReceiptUnreducedTooLargeException(val uploaded: DocumentSize, val limit: DocumentSize) :
    ApplicationValidationException(
        "Unreduced receipt of ${uploaded.mebiBytes.toInt()} MiB exceeds the maximum of ${limit.mebiBytes.toInt()} MiB.",
    )

/** [de.littletrotter.application.invoice.port.outbound.ReceiptPdfReducer] tried every reduction
 *  stage and the result still exceeds the target — [pageCount] and [resultingSize] let the caller
 *  phrase a precise rejection instead of a generic "too large". */
class ReceiptReductionFailedException(val pageCount: Int, val resultingSize: DocumentSize) :
    ApplicationValidationException(
        "Receipt could not be reduced below the target size: $pageCount page(s), ${resultingSize.mebiBytes.toInt()} MiB remaining.",
    )
