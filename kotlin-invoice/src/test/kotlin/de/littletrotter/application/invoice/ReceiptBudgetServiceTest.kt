package de.littletrotter.application.invoice

import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.shared.receipt.DocumentSize
import de.littletrotter.shared.receipt.ReceiptSize
import de.littletrotter.shared.receipt.ReceiptSizePolicy
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock

/**
 * [ReceiptBudgetService.requireWithinBudget]'s two independent guards: the receipt-count limit is
 * still refused outright (a distinct-receipt cap the write-path guards
 * ([ReceiptLinkService.setReceiptsForItem]/[ReceiptLinkService.setReceiptsForInvoice]) must never
 * let through), while [de.littletrotter.shared.receipt.BudgetVerdict.DocumentTooLarge] is now
 * advisory only at link time — the estimated size is only checked (and refused) once, right before
 * an actual document is generated, in [GenerationJobCreationService]. No repository interaction
 * needed here: [ReceiptBudgetService.requireWithinBudget] takes the already-resolved
 * [ReceiptSize] list, so the mocked [ReceiptRepository] only satisfies the constructor.
 */
class ReceiptBudgetServiceTest {

    private val receiptRepository: ReceiptRepository = mock()
    private val service = ReceiptBudgetService(receiptRepository)

    private fun receiptSize(id: Long, size: DocumentSize) = ReceiptSize("receipt-pool/receipt-$id.pdf", size)

    @Test
    fun `19 receipts at the unreduced cap stay within budget even though the estimate exceeds the document limit`() {
        // 19 x 1 MiB is exactly ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE at
        // MAX_UNREDUCED_RECEIPT_SIZE — Base64 encoding plus the 1 MiB reserve pushes the estimate
        // past MAX_INVOICE_DOCUMENT_SIZE, but that verdict is advisory only at link time.
        val receipts = (1L..19L).map { id -> receiptSize(id, ReceiptSizePolicy.MAX_UNREDUCED_RECEIPT_SIZE) }

        assertDoesNotThrow { service.requireWithinBudget(receipts) }
    }

    @Test
    fun `20 distinct receipts are refused outright regardless of size`() {
        val receipts = (1L..20L).map { id -> receiptSize(id, DocumentSize.ofBytes(1L)) }

        val ex = assertThrows<ReceiptCountLimitExceededException> { service.requireWithinBudget(receipts) }

        assertEquals(ReceiptSizePolicy.MAX_RECEIPTS_PER_INVOICE, ex.limit)
        assertEquals(20, ex.attempted)
    }

    @Test
    fun `a within-policy receipt set passes without any guard firing`() {
        val receipts = listOf(receiptSize(1L, DocumentSize.ofMebiBytes(1L)))

        assertDoesNotThrow { service.requireWithinBudget(receipts) }
    }
}
