package de.littletrotter.application.invoice

import de.littletrotter.application.error.ApplicationValidationException
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.shared.receipt.BudgetVerdict
import de.littletrotter.shared.receipt.DocumentSize
import de.littletrotter.shared.receipt.InvoiceDocumentBudget
import de.littletrotter.shared.receipt.ReceiptSize
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional

/**
 * The invoice document budget ("export/annex" sizing). Computes the invoice-wide
 * resulting receipt set the write-path guards in [ReceiptLinkService] must budget against, and
 * refuses (never truncates) a candidate set that would exceed the receipt-count limit — dropping
 * the surplus silently would lose a proof. The document-size limit is advisory at link time (see
 * [requireWithinBudget]); [invoiceDocumentBudget] still lets callers show the current size, and
 * [GenerationJobCreationService] enforces the hard stop before a document is actually generated.
 */
@ApplicationScoped
class ReceiptBudgetService @Inject constructor(
    private val receiptRepository: ReceiptRepository,
) {
    companion object {
        /** Page size used where the *complete* set is required (the budget calculations read every
         *  attached receipt, never a page). Named so the intent is not mistaken for an arbitrarily
         *  large page. */
        private const val UNBOUNDED_PAGE_SIZE = Int.MAX_VALUE
    }

    /**
     * The invoice's [InvoiceDocumentBudget] **as it currently stands** (no candidate set):
     * this invoice's own invoice-level receipts plus every item's own receipts, deduplicated by
     * object key — the same resulting-set composition the write-path guards use. Renders
     * counts and sizes from this directly and must not recompute anything itself.
     *
     * One call per dialog open / per reload — not per grid row (the N+1 `findUsageSummaries`
     * already exists to avoid).
     */
    @Transactional
    fun invoiceDocumentBudget(invoiceId: Long): InvoiceDocumentBudget {
        val invoiceLevelReceipts = receiptRepository.findForInvoice(invoiceId, 0, UNBOUNDED_PAGE_SIZE)
        val itemReceipts = receiptRepository.findItemReceiptsForInvoice(invoiceId).values.flatten()
        return InvoiceDocumentBudget.calculate((invoiceLevelReceipts + itemReceipts).map { it.toReceiptSize() })
    }

    /** The invoice-wide resulting receipt set [ReceiptLinkService.setReceiptsForItem] must
     *  budget against: the invoice's own invoice-level receipts, plus every *other* item's own
     *  receipts, plus the candidate [newReceipts] this write would give [invoiceItemId]. The write
     *  paths replace the full set, so the guard must see the invoice as a whole, not just this
     *  write's own ids. */
    fun resultingReceiptsForItem(
        invoiceId: Long,
        invoiceItemId: Long,
        newReceipts: List<Receipt>,
    ): List<ReceiptSize> {
        val invoiceLevelReceipts = receiptRepository.findForInvoice(invoiceId, 0, UNBOUNDED_PAGE_SIZE)
        val otherItemsReceipts = receiptRepository.findItemReceiptsForInvoice(invoiceId)
            .filterKeys { it != invoiceItemId }
            .values
            .flatten()
        return (invoiceLevelReceipts + otherItemsReceipts + newReceipts).map { it.toReceiptSize() }
    }

    /** The invoice-wide resulting receipt set [ReceiptLinkService.setReceiptsForInvoice]
     *  must budget against: the candidate [newReceipts] this write would give the invoice level,
     *  plus every item's own receipts (untouched by this write). The invoice's *current*
     *  invoice-level receipts are irrelevant here — [newReceipts] replaces them outright. */
    fun resultingReceiptsForInvoice(invoiceId: Long, newReceipts: List<Receipt>): List<ReceiptSize> {
        val allItemReceipts = receiptRepository.findItemReceiptsForInvoice(invoiceId).values.flatten()
        return (newReceipts + allItemReceipts).map { it.toReceiptSize() }
    }

    /** Converts a persisted [Receipt] into [ReceiptSize] for the budget calculator.
     *
     *  [Receipt.sizeBytes] is nullable in the entity but [Receipt.validate] requires it before a
     *  receipt is ever persisted through upload; a null here means the row bypassed that (a
     *  stale fixture, a manual insert, a defect). PO decision: refuse rather than treat it
     *  as [DocumentSize.ZERO] — silently counting an unknown size as zero is exactly how a 20 MiB
     *  budget would let the resulting document past 20 MiB. */
    private fun Receipt.toReceiptSize(): ReceiptSize {
        val bytes = sizeBytes
            ?: throw ApplicationValidationException(
                "Receipt ${id ?: "?"} ($objectKey) has no known size and cannot be included in the invoice document budget.",
            )
        return ReceiptSize(objectKey, DocumentSize.ofBytes(bytes))
    }

    /** The one place a [BudgetVerdict] is mapped to an exception: both write-path guards
     *  ([ReceiptLinkService.setReceiptsForItem], [ReceiptLinkService.setReceiptsForInvoice]) funnel
     *  their invoice-wide resulting receipt set through here. The count refusal (never truncating)
     *  is unchanged — silently dropping the surplus would lose a proof. [BudgetVerdict.DocumentTooLarge]
     *  is deliberately *not* refused here any more (PO decision): the user can still reduce or
     *  remove receipts before the invoice is actually generated, so blocking the link itself would
     *  only get in the way. The link succeeds, the editor shows a warning
     *  ([de.littletrotter.ui.view.invoice.InvoiceReceiptsTab]), and the hard stop moves to the one
     *  place it actually matters: in front of document generation
     *  ([GenerationJobCreationService.createJob]). */
    fun requireWithinBudget(resultingReceipts: List<ReceiptSize>) {
        when (val verdict = InvoiceDocumentBudget.calculate(resultingReceipts).verdict) {
            is BudgetVerdict.TooManyReceipts ->
                throw ReceiptCountLimitExceededException(verdict.limit, verdict.attempted)
            is BudgetVerdict.DocumentTooLarge -> Unit
            BudgetVerdict.Accepted -> Unit
        }
    }
}

/**
 * The invoice-wide resulting receipt set (this write's ids plus everything else the
 * invoice already carries, deduplicated by object key) would exceed
 * [de.littletrotter.shared.receipt.ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE] distinct receipts.
 * Refused rather than truncated: dropping the surplus silently would lose a proof.
 *
 * Carries [limit] and [attempted] as data so the UI can phrase the message itself; the
 * message text here is the fallback for logs and for callers that only forward `message`.
 */
class ReceiptCountLimitExceededException(val limit: Int, val attempted: Int) :
    RuntimeException(
        "At most $limit distinct receipts are allowed per invoice, $attempted were requested. " +
            "Please remove receipts that are no longer needed first.",
    )

/**
 * The invoice's current receipt set fits
 * [de.littletrotter.shared.receipt.ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE], but its estimated
 * document size ([InvoiceDocumentBudget]) would exceed
 * [de.littletrotter.shared.receipt.ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE]. Thrown only by
 * [GenerationJobCreationService.createJob] — linking a receipt no longer throws this (PO decision):
 * the user can still reduce or remove receipts before generation is attempted again.
 *
 * Carries [limit] and [estimated] as data so the UI can phrase the message itself; the
 * message text here is the fallback for logs and for callers that only forward `message`.
 */
class InvoiceDocumentSizeExceededException(val limit: DocumentSize, val estimated: DocumentSize) :
    RuntimeException(
        "The invoice document would grow to $estimated, exceeding the maximum of $limit. " +
            "Please remove receipts that are no longer needed first.",
    )
