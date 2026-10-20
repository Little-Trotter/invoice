package de.littletrotter.shared.receipt

/**
 * The outcome of evaluating an [InvoiceDocumentBudget] — data, never an exception.
 *
 * `kotlin-shared` deliberately does not decide how a refusal surfaces: `kotlin-invoice`'s
 * application layer (IR-2) maps a verdict to an application exception, and the UI (IR-3) maps it
 * to a message key. Keeping the verdict as a sealed value here instead of throwing lets both
 * consumers make that choice without this package importing either concern.
 */
sealed interface BudgetVerdict {

    /** Both limits hold: the receipt count and the estimated document size are within policy. */
    data object Accepted : BudgetVerdict

    /**
     * The receipt count alone exceeds [limit] — reported even when the estimated size also
     * exceeds policy, because [InvoiceDocumentBudget] always reports the count first: it is the
     * rule a user can act on without doing arithmetic.
     */
    data class TooManyReceipts(val limit: Int, val attempted: Int) : BudgetVerdict

    /** The receipt count is within policy, but the estimated document size exceeds [limit]. */
    data class DocumentTooLarge(val limit: DocumentSize, val estimated: DocumentSize) : BudgetVerdict
}
