package de.littletrotter.shared.receipt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InvoiceDocumentSizeEstimatorTest {

    @Test
    fun `Base64WithReserveEstimator sums base64-encoded bytes and adds the default reserve`() {
        val receipts = listOf(
            ReceiptSize("k1", DocumentSize.ofBytes(1_000L)),
            ReceiptSize("k2", DocumentSize.ofBytes(2_000L)),
        )
        val estimated = Base64WithReserveEstimator().estimate(receipts)
        val expectedReceiptBytes = DocumentSize.ofBytes(1_000L).base64Encoded() + DocumentSize.ofBytes(2_000L).base64Encoded()
        assertEquals(ReceiptSizePolicy.INVOICE_DOCUMENT_RESERVE + expectedReceiptBytes, estimated)
    }

    @Test
    fun `Base64WithReserveEstimator of an empty set is exactly the reserve`() {
        assertEquals(ReceiptSizePolicy.INVOICE_DOCUMENT_RESERVE, Base64WithReserveEstimator().estimate(emptyList()))
    }

    @Test
    fun `Base64WithReserveEstimator rounds each receipt up to a 4-byte block`() {
        val estimated = Base64WithReserveEstimator().estimate(listOf(ReceiptSize("k1", DocumentSize.ofBytes(1L))))
        assertEquals(ReceiptSizePolicy.INVOICE_DOCUMENT_RESERVE + DocumentSize.ofBytes(4L), estimated)
    }

    @Test
    fun `Base64WithReserveEstimator accepts a custom reserve`() {
        val estimator = Base64WithReserveEstimator(reserve = DocumentSize.ofBytes(500L))
        val estimated = estimator.estimate(listOf(ReceiptSize("k1", DocumentSize.ofBytes(1_000L))))
        assertEquals(DocumentSize.ofBytes(500L) + DocumentSize.ofBytes(1_000L).base64Encoded(), estimated)
    }

    @Test
    fun `InvoiceDocumentSizeEstimator is a SAM interface usable as a lambda`() {
        val estimator = InvoiceDocumentSizeEstimator { receipts -> DocumentSize.ofBytes(receipts.size.toLong()) }
        assertEquals(
            DocumentSize.ofBytes(2L),
            estimator.estimate(listOf(ReceiptSize("a", DocumentSize.ZERO), ReceiptSize("b", DocumentSize.ZERO))),
        )
    }

    /**
     * Ties [DocumentSize.base64Encoded], [ReceiptSizePolicy]'s constants and the default estimator
     * together at the point they were designed to meet exactly: 19 receipts reduced to the 768 KiB
     * target, Base64-encoded, plus the 1 MiB reserve, is exactly the 20 MiB document limit.
     */
    @Test
    fun `19 receipts at the target size is accepted at exactly the document limit`() {
        val receipts = (1..19).map { ReceiptSize("key-$it", ReceiptSizePolicy.TARGET_RECEIPT_SIZE) }
        val budget = InvoiceDocumentBudget.calculate(receipts)

        assertEquals(ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE, budget.estimatedDocumentSize)
        assertEquals(BudgetVerdict.Accepted, budget.verdict)
    }

    /**
     * 19 unreduced receipts at 1 MiB Base64-encode to 19 × ⌈1 MiB × 4/3⌉ ≈ 25.33 MiB, plus the 1
     * MiB reserve — comfortably over the 20 MiB limit.
     */
    @Test
    fun `19 receipts at the unreduced cap is refused as document too large`() {
        val receipts = (1..19).map { ReceiptSize("key-$it", ReceiptSizePolicy.MAX_UNREDUCED_RECEIPT_SIZE) }
        val budget = InvoiceDocumentBudget.calculate(receipts)

        assertEquals(
            BudgetVerdict.DocumentTooLarge(
                limit = ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE,
                estimated = budget.estimatedDocumentSize,
            ),
            budget.verdict,
        )
    }
}
