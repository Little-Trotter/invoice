package de.littletrotter.shared.receipt

/**
 * One receipt's raw size, keyed by [objectKey] rather than by receipt id.
 *
 * Receipts are n:m with invoice positions: the same document commonly hangs off a position
 * *and* off the invoice as a whole, and the export path already deduplicates by object key (see
 * `MustangExportModelMapper.collectReceipts` in `kotlin-xpdf`). [InvoiceDocumentBudget] deduplicates the same way — counting by receipt id
 * instead would refuse invoices the exporter handles fine.
 */
data class ReceiptSize(
    val objectKey: String,
    val size: DocumentSize,
    /** Recorded at upload; `null` for a receipt without a recorded profile, which is sized by its bytes alone. */
    val profile: ReceiptPageProfile? = null,
)
