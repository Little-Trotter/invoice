package de.littletrotter.shared

/**
 * A receipt reference on the job contract. Fields are only ever added, never changed: [description]
 * is the user-entered receipt description, absent for senders that predate it.
 */
data class ReceiptDto(
    val fileName: String? = null,
    val objectKey: String? = null,
    val description: String? = null,
    /** `ReceiptRasterKind` name recorded at upload; absent for senders that predate it. */
    val rasterKind: String? = null,
    val pageCount: Int? = null,
    val a4Factor: Double? = null,
    /** Bytes the receipt's pages weigh in the printed annex, measured at upload; absent for older senders. */
    val annexBytes: Long? = null,
)
