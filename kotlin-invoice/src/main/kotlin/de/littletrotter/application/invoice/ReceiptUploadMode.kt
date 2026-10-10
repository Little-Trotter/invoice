package de.littletrotter.application.invoice

/**
 * How [ReceiptUploadService.upload] treats a receipt that exceeds `ReceiptSizePolicy
 * .TARGET_RECEIPT_SIZE`: the default reduces it, the alternative lets the caller keep the
 * original bytes up to the smaller unreduced cap instead.
 */
enum class ReceiptUploadMode {
    /** Reduce towards the target via [de.littletrotter.application.invoice.port.outbound.ReceiptPdfReducer]. */
    REDUCE,

    /** Keep the uploaded bytes as-is; refused above `ReceiptSizePolicy.MAX_UNREDUCED_RECEIPT_SIZE`. */
    KEEP_UNREDUCED,
}
