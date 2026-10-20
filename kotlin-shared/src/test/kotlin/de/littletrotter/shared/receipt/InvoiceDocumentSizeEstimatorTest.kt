package de.littletrotter.shared.receipt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InvoiceDocumentSizeEstimatorTest {

    @Test
    fun `CombinedDocumentSizeEstimator sums base64-encoded bytes and adds the default reserve`() {
        val receipts = listOf(
            ReceiptSize("k1", DocumentSize.ofBytes(1_000L)),
            ReceiptSize("k2", DocumentSize.ofBytes(2_000L)),
        )
        val estimated = CombinedDocumentSizeEstimator(rasterFactor = 0).estimate(receipts)
        val expectedReceiptBytes = DocumentSize.ofBytes(1_000L).base64Encoded() + DocumentSize.ofBytes(2_000L).base64Encoded()
        assertEquals(ReceiptSizePolicy.INVOICE_DOCUMENT_RESERVE + expectedReceiptBytes, estimated)
    }

    @Test
    fun `CombinedDocumentSizeEstimator of an empty set is exactly the reserve`() {
        assertEquals(ReceiptSizePolicy.INVOICE_DOCUMENT_RESERVE, CombinedDocumentSizeEstimator().estimate(emptyList()))
    }

    @Test
    fun `CombinedDocumentSizeEstimator rounds each receipt up to a 4-byte block`() {
        val estimated = CombinedDocumentSizeEstimator(rasterFactor = 0).estimate(listOf(ReceiptSize("k1", DocumentSize.ofBytes(1L))))
        assertEquals(ReceiptSizePolicy.INVOICE_DOCUMENT_RESERVE + DocumentSize.ofBytes(4L), estimated)
    }

    @Test
    fun `CombinedDocumentSizeEstimator accepts a custom reserve`() {
        val estimator = CombinedDocumentSizeEstimator(reserve = DocumentSize.ofBytes(500L), rasterFactor = 0)
        val estimated = estimator.estimate(listOf(ReceiptSize("k1", DocumentSize.ofBytes(1_000L))))
        assertEquals(DocumentSize.ofBytes(500L) + DocumentSize.ofBytes(1_000L).base64Encoded(), estimated)
    }

    @Test
    fun `the default estimator adds the rasterised pages, about the receipt size again, on top of Base64`() {
        val receipt = ReceiptSize("k1", DocumentSize.ofBytes(3L))
        assertEquals(
            ReceiptSizePolicy.INVOICE_DOCUMENT_RESERVE + DocumentSize.ofBytes(4L) + DocumentSize.ofBytes(3L),
            CombinedDocumentSizeEstimator().estimate(listOf(receipt)),
        )
    }

    @Test
    fun `a negative raster factor is rejected`() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            CombinedDocumentSizeEstimator(rasterFactor = -1)
        }
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
     * together: 19 receipts reduced to the 440 KiB target, counted as Base64 and as rasterised
     * pages, plus the reserve, are accepted.
     */
    @Test
    fun `19 receipts at the target size are accepted including their pages`() {
        val receipts = (1..19).map { ReceiptSize("key-$it", ReceiptSizePolicy.TARGET_RECEIPT_SIZE) }
        val budget = InvoiceDocumentBudget.calculate(receipts)

        assertEquals(BudgetVerdict.Accepted, budget.verdict)
        assertTrue(budget.estimatedDocumentSize <= ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE)
    }

    /**
     * 19 unreduced receipts at 1 MiB Base64-encode to 19 × ⌈1 MiB × 4/3⌉ ≈ 25.33 MiB, plus the 1
     * MiB reserve — comfortably over the 20 MiB limit.
     */
    @Test
    fun `19 receipts at the unreduced cap is refused as document too large`() {
        val receipts = (1..19).map { ReceiptSize("key-$it", ReceiptSizePolicy.MAX_UNREDUCED_RECEIPT_SIZE) }
        val budget = InvoiceDocumentBudget.calculate(receipts, CombinedDocumentSizeEstimator(rasterFactor = 0))

        assertEquals(
            BudgetVerdict.DocumentTooLarge(
                limit = ReceiptSizePolicy.MAX_INVOICE_DOCUMENT_SIZE,
                estimated = budget.estimatedDocumentSize,
            ),
            budget.verdict,
        )
    }

    @Test
    fun `an image receipt weighs its own bytes again as pages`() {
        val bytes = 400_000L
        val profile = ReceiptPageProfile(pageCount = 3, a4Factor = 3.0, rasterKind = ReceiptRasterKind.IMAGE_PDF, annexBytes = bytes)
        val receipt = ReceiptSize("k", DocumentSize.ofBytes(bytes), profile)

        val estimated = CombinedDocumentSizeEstimator().estimate(listOf(receipt))

        assertEquals(
            ReceiptSizePolicy.INVOICE_DOCUMENT_RESERVE + receipt.size.base64Encoded() + receipt.size,
            estimated,
        )
    }

    @Test
    fun `a document receipt weighs the annex bytes measured at upload, whatever its stored size`() {
        val profile = ReceiptPageProfile(pageCount = 2, a4Factor = 1.5, rasterKind = ReceiptRasterKind.DOCUMENT, annexBytes = 1_700_000L)
        val receipt = ReceiptSize("k", DocumentSize.ofBytes(10_000L), profile)

        val estimated = CombinedDocumentSizeEstimator().estimate(listOf(receipt))

        assertEquals(
            ReceiptSizePolicy.INVOICE_DOCUMENT_RESERVE + receipt.size.base64Encoded() + DocumentSize.ofBytes(1_700_000L),
            estimated,
        )
    }

    @Test
    fun `a profile without a measured annex size is not accepted`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReceiptPageProfile(pageCount = 1, a4Factor = 1.0, rasterKind = ReceiptRasterKind.DOCUMENT, annexBytes = 0L)
        }
    }

    @Test
    fun `a receipt without a profile keeps the byte-based raster factor`() {
        val receipt = ReceiptSize("k", DocumentSize.ofBytes(1_000L))

        val estimated = CombinedDocumentSizeEstimator(rasterFactor = 2).estimate(listOf(receipt))

        assertEquals(ReceiptSizePolicy.INVOICE_DOCUMENT_RESERVE + receipt.size.base64Encoded() + receipt.size * 2, estimated)
    }
}
