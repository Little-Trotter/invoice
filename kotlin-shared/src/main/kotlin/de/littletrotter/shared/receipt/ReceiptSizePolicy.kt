package de.littletrotter.shared.receipt

/**
 * IR-1 — the numbers behind the invoice document size budget, in one place, so
 * `kotlin-invoice`'s upload guard, its per-invoice budget, `kotlin-xpdf`'s export backstop and
 * the UI's hint text all read the same values instead of each keeping its own copy.
 *
 * The numbers add up on purpose: [MAX_RECEIPTS_PER_INVOICE] receipts at [TARGET_RECEIPT_SIZE]
 * each, counted twice (Base64 text inside the XML, BG-24, and rasterised pages in the PDF, see
 * [CombinedDocumentSizeEstimator]), plus [INVOICE_DOCUMENT_RESERVE] for the invoice document itself,
 * still fit into [MAX_INVOICE_DOCUMENT_SIZE]. `ReceiptSizePolicyTest` pins this invariant so a
 * future edit to one constant cannot silently leave a limit that is unreachable or fires before
 * another one does.
 *
 * The byte-typed `const val`s exist alongside the [de.littletrotter.shared.receipt.DocumentSize]
 * ones because a `const val` must be a compile-time constant of a primitive/String type — Java
 * annotation defaults and other compile-time-constant call sites (IR-4) cannot consume a
 * [de.littletrotter.shared.receipt.DocumentSize] value, only the raw `Long`.
 */
object ReceiptSizePolicy {

    /** Bytes a receipt is reduced to at upload — see [TARGET_RECEIPT_SIZE]. */
    const val TARGET_RECEIPT_SIZE_BYTES: Long = 440L * 1024

    /** Bytes in the hard cap applied when the user declines reduction — see [MAX_UNREDUCED_RECEIPT_SIZE]. */
    const val MAX_UNREDUCED_RECEIPT_SIZE_BYTES: Long = 1L * 1024 * 1024

    /** Bytes in the largest raw upload accepted when reduction is applied — see [MAX_UPLOAD_SIZE]. */
    const val MAX_UPLOAD_SIZE_BYTES: Long = 10L * 1024 * 1024

    /** Distinct receipts (deduplicated by object key) a single invoice document may embed. */
    const val MAX_RECEIPTS_PER_INVOICE: Int = 19

    /** Bytes in the produced e-invoice's hard limit — see [MAX_INVOICE_DOCUMENT_SIZE]. */
    const val MAX_INVOICE_DOCUMENT_SIZE_BYTES: Long = 20L * 1024 * 1024

    /**
     * Measured size of everything in the produced PDF that is neither a receipt page nor the
     * Base64 receipt text: the invoice base pages, PDF/A-3 catalog, XMP and output intent
     * (about 50 KiB with the test base document).
     */
    const val MEASURED_FIXED_PART_BYTES: Long = 50L * 1024

    /** Multiple of the reserve is rounded up to, so it stays a round figure. */
    private const val RESERVE_GRANULARITY_BYTES: Long = 64L * 1024

    /**
     * How many times the measured fixed part is reserved: the measurement used a small test base, a
     * real base with logo and fonts is larger, and the annex header bands add text to every receipt page.
     */
    private const val RESERVE_FACTOR: Long = 5

    /**
     * Bytes reserved for the invoice document itself — see [INVOICE_DOCUMENT_RESERVE]: the measured
     * fixed part times [RESERVE_FACTOR], rounded up to a multiple of 64 KiB (256 KiB).
     */
    const val INVOICE_DOCUMENT_RESERVE_BYTES: Long =
        (MEASURED_FIXED_PART_BYTES * RESERVE_FACTOR + RESERVE_GRANULARITY_BYTES - 1) / RESERVE_GRANULARITY_BYTES * RESERVE_GRANULARITY_BYTES

    /** The size a receipt is reduced to at upload; see [meetsTarget] and [uploadDecision]. */
    val TARGET_RECEIPT_SIZE: DocumentSize = DocumentSize.ofBytes(TARGET_RECEIPT_SIZE_BYTES)

    /** Hard cap when the user declines reduction; see [acceptsUnreduced]. */
    val MAX_UNREDUCED_RECEIPT_SIZE: DocumentSize = DocumentSize.ofBytes(MAX_UNREDUCED_RECEIPT_SIZE_BYTES)

    /** Largest raw upload accepted at all, above which reduction cannot help; see [uploadDecision]. */
    val MAX_UPLOAD_SIZE: DocumentSize = DocumentSize.ofBytes(MAX_UPLOAD_SIZE_BYTES)

    /** Hard limit for the produced e-invoice document, checked by the budget and the export backstop. */
    val MAX_INVOICE_DOCUMENT_SIZE: DocumentSize = DocumentSize.ofBytes(MAX_INVOICE_DOCUMENT_SIZE_BYTES)

    /** The invoice document's own share of [MAX_INVOICE_DOCUMENT_SIZE], on top of the receipts. */
    val INVOICE_DOCUMENT_RESERVE: DocumentSize = DocumentSize.ofBytes(INVOICE_DOCUMENT_RESERVE_BYTES)
}

/** The upload guard's three-way outcome for a raw upload's size — see [ReceiptSizePolicy.uploadDecision]. */
enum class ReceiptUploadDecision { ACCEPT_AS_IS, OFFER_REDUCTION, REJECT }

/**
 * The upload guard's verdict for a raw upload (IR-2): at or below [ReceiptSizePolicy.TARGET_RECEIPT_SIZE]
 * it is stored as-is; above that but still at or below [ReceiptSizePolicy.MAX_UPLOAD_SIZE] the user is
 * offered reduction to the target; beyond that the upload is refused outright.
 */
fun ReceiptSizePolicy.uploadDecision(size: DocumentSize): ReceiptUploadDecision = when {
    size <= TARGET_RECEIPT_SIZE -> ReceiptUploadDecision.ACCEPT_AS_IS
    size <= MAX_UPLOAD_SIZE -> ReceiptUploadDecision.OFFER_REDUCTION
    else -> ReceiptUploadDecision.REJECT
}

/** True at or below [ReceiptSizePolicy.TARGET_RECEIPT_SIZE] — the size a reduced receipt ends up at. */
fun ReceiptSizePolicy.meetsTarget(size: DocumentSize): Boolean = size <= TARGET_RECEIPT_SIZE

/**
 * True at or below [ReceiptSizePolicy.MAX_UNREDUCED_RECEIPT_SIZE] — the hard cap enforced when the
 * user declines reduction, only a size strictly greater is refused.
 */
fun ReceiptSizePolicy.acceptsUnreduced(size: DocumentSize): Boolean = size <= MAX_UNREDUCED_RECEIPT_SIZE
