package de.littletrotter.shared.receipt

/**
 * Answers the one question this subdomain owns: given the receipt set an invoice would end up
 * with, does it still fit the [ReceiptSizePolicy] budget, and how much room is left?
 *
 * Built via [calculate], never via the constructor directly from outside this package's tests —
 * the constructor is public only because it is a plain data holder; [calculate] is the one place
 * that keeps every field consistent with the others.
 *
 * The input is expected to be **the resulting receipt set**, not a delta: `ReceiptService`'s
 * write paths (IR-2) replace the full set of receipts linked to an invoice, so "the set after the
 * write" is the natural input — evaluating a partial set would report a budget that does not
 * match what gets persisted.
 */
data class InvoiceDocumentBudget(
    /** Distinct receipts after deduplication by object key — see [ReceiptSize]. */
    val distinctReceiptCount: Int,
    /** Deduplicated sum of the receipts' raw sizes. */
    val receiptSize: DocumentSize,
    /** [InvoiceDocumentSizeEstimator.estimate] applied to the deduplicated receipt set. */
    val estimatedDocumentSize: DocumentSize,
    /** [ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE] minus [estimatedDocumentSize], clamped at zero. */
    val remainingSize: DocumentSize,
    /** [ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE] minus [distinctReceiptCount], clamped at zero. */
    val remainingSlots: Int,
    /** True once either limit has no room left — by count, by size, or both. */
    val isFull: Boolean,
    val verdict: BudgetVerdict,
) {

    companion object {

        /**
         * Evaluates [receipts] against [ReceiptSizePolicy], deduplicating by
         * [ReceiptSize.objectKey] first.
         *
         * When both limits are broken, [BudgetVerdict.TooManyReceipts] is reported over
         * [BudgetVerdict.DocumentTooLarge] — the count is the rule a user can act on without
         * arithmetic (PO decision, IR-1).
         */
        fun calculate(
            receipts: Collection<ReceiptSize>,
            estimator: InvoiceDocumentSizeEstimator = Base64WithReserveEstimator(),
        ): InvoiceDocumentBudget {
            val distinctReceipts = receipts.distinctBy { it.objectKey }
            val receiptSize = DocumentSize.sum(distinctReceipts.map { it.size })
            val estimatedDocumentSize = estimator.estimate(distinctReceipts)

            val remainingSize = ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE.remainingAfter(estimatedDocumentSize)
            val remainingSlots =
                (ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE - distinctReceipts.size).coerceAtLeast(0)

            val verdict = when {
                distinctReceipts.size > ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE ->
                    BudgetVerdict.TooManyReceipts(
                        limit = ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE,
                        attempted = distinctReceipts.size,
                    )
                estimatedDocumentSize > ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE ->
                    BudgetVerdict.DocumentTooLarge(
                        limit = ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE,
                        estimated = estimatedDocumentSize,
                    )
                else -> BudgetVerdict.Accepted
            }

            return InvoiceDocumentBudget(
                distinctReceiptCount = distinctReceipts.size,
                receiptSize = receiptSize,
                estimatedDocumentSize = estimatedDocumentSize,
                remainingSize = remainingSize,
                remainingSlots = remainingSlots,
                isFull = remainingSlots == 0 || remainingSize == DocumentSize.ZERO,
                verdict = verdict,
            )
        }
    }
}
