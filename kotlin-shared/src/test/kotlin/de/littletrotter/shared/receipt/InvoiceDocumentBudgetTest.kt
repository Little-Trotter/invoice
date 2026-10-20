package de.littletrotter.shared.receipt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InvoiceDocumentBudgetTest {

    private fun receiptsOfSize(count: Int, size: DocumentSize): List<ReceiptSize> =
        (1..count).map { ReceiptSize("key-$it", size) }

    @Test
    fun `empty receipt set is accepted, full budget remaining`() {
        val budget = InvoiceDocumentBudget.calculate(emptyList())

        assertEquals(0, budget.distinctReceiptCount)
        assertEquals(DocumentSize.ZERO, budget.receiptSize)
        assertEquals(ReceiptSizePolicy.INVOICE_DOCUMENT_RESERVE, budget.estimatedDocumentSize)
        assertEquals(
            ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE - ReceiptSizePolicy.INVOICE_DOCUMENT_RESERVE,
            budget.remainingSize,
        )
        assertEquals(ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE, budget.remainingSlots)
        assertFalse(budget.isFull)
        assertEquals(BudgetVerdict.Accepted, budget.verdict)
    }

    @Test
    fun `single receipt is accepted`() {
        val budget = InvoiceDocumentBudget.calculate(listOf(ReceiptSize("key-1", DocumentSize.ofBytes(100L))))

        assertEquals(1, budget.distinctReceiptCount)
        assertEquals(DocumentSize.ofBytes(100L), budget.receiptSize)
        assertEquals(ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE - 1, budget.remainingSlots)
        assertFalse(budget.isFull)
        assertEquals(BudgetVerdict.Accepted, budget.verdict)
    }

    /**
     * The export path already deduplicates by object key (see [ReceiptSize]'s KDoc); the budget
     * must agree, or it would refuse invoices the exporter handles fine. distinctBy keeps the
     * first occurrence, so the size that "wins" on a duplicate key is the first one supplied.
     */
    @Test
    fun `duplicate object keys collapse into one distinct receipt`() {
        val receipts = listOf(
            ReceiptSize("shared-key", DocumentSize.ofBytes(100L)),
            ReceiptSize("shared-key", DocumentSize.ofBytes(999L)),
            ReceiptSize("other-key", DocumentSize.ofBytes(50L)),
        )

        val budget = InvoiceDocumentBudget.calculate(receipts)

        assertEquals(2, budget.distinctReceiptCount)
        assertEquals(DocumentSize.ofBytes(150L), budget.receiptSize)
        assertEquals(BudgetVerdict.Accepted, budget.verdict)
    }

    @Test
    fun `exactly 19 distinct receipts is accepted with zero slots remaining`() {
        val budget = InvoiceDocumentBudget.calculate(receiptsOfSize(19, DocumentSize.ofBytes(1L)))

        assertEquals(19, budget.distinctReceiptCount)
        assertEquals(0, budget.remainingSlots)
        assertTrue(budget.isFull)
        assertEquals(BudgetVerdict.Accepted, budget.verdict)
    }

    @Test
    fun `20 distinct receipts is refused, count reported before size`() {
        val budget = InvoiceDocumentBudget.calculate(receiptsOfSize(20, DocumentSize.ofBytes(1L)))

        assertEquals(20, budget.distinctReceiptCount)
        assertEquals(0, budget.remainingSlots)
        assertTrue(budget.isFull)
        assertEquals(BudgetVerdict.TooManyReceipts(limit = 19, attempted = 20), budget.verdict)
    }

    @Test
    fun `estimated document size exactly at the limit is accepted`() {
        val budget = InvoiceDocumentBudget.calculate(
            receiptsOfSize(19, ReceiptSizePolicy.TARGET_RECEIPT_SIZE),
            InvoiceDocumentSizeEstimator { ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE },
        )

        assertEquals(ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE, budget.estimatedDocumentSize)
        assertEquals(DocumentSize.ZERO, budget.remainingSize)
        assertTrue(budget.isFull)
        assertEquals(BudgetVerdict.Accepted, budget.verdict)
    }

    @Test
    fun `estimated document size one byte over the limit is refused`() {
        // Stay at 18 receipts (under the count limit) so only the size limit fires, isolating it
        // from the TooManyReceipts branch.
        val estimator = InvoiceDocumentSizeEstimator {
            ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE + DocumentSize.ofBytes(1L)
        }

        val budget = InvoiceDocumentBudget.calculate(receiptsOfSize(18, DocumentSize.ofBytes(1L)), estimator)

        assertEquals(18, budget.distinctReceiptCount)
        assertEquals(DocumentSize.ZERO, budget.remainingSize)
        assertTrue(budget.isFull)
        assertEquals(
            BudgetVerdict.DocumentTooLarge(
                limit = ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE,
                estimated = ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE + DocumentSize.ofBytes(1L),
            ),
            budget.verdict,
        )
    }

    @Test
    fun `both limits broken at once reports the count, not the size`() {
        val estimator = InvoiceDocumentSizeEstimator {
            ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE + DocumentSize.ofBytes(1L)
        }

        val budget = InvoiceDocumentBudget.calculate(receiptsOfSize(20, DocumentSize.ofBytes(1L)), estimator)

        assertEquals(BudgetVerdict.TooManyReceipts(limit = 19, attempted = 20), budget.verdict)
        assertTrue(budget.isFull)
    }

    @Test
    fun `a custom estimator is honoured`() {
        val estimator = InvoiceDocumentSizeEstimator { DocumentSize.ofBytes(123L) }
        val budget = InvoiceDocumentBudget.calculate(
            listOf(ReceiptSize("key-1", DocumentSize.ofBytes(1L))),
            estimator,
        )
        assertEquals(DocumentSize.ofBytes(123L), budget.estimatedDocumentSize)
    }

    @Test
    fun `copy and destructuring work like any data class`() {
        val budget = InvoiceDocumentBudget.calculate(emptyList())
        val copy = budget.copy(distinctReceiptCount = 5)
        assertEquals(5, copy.distinctReceiptCount)

        val (distinctReceiptCount) = budget
        assertEquals(0, distinctReceiptCount)
    }
}
