package de.littletrotter.application.invoice

import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.fixture.aReceipt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDate

/**
 * [ReceiptBudgetService.invoiceDocumentBudget] for a
 * trip: a receipt attached to a trip item is counted once, even though a
 * [de.littletrotter.domain.invoice.TripExpense.receipt] on one of the trip's own expense lines
 * points at that very same row. [ReceiptBudgetService] never queries `trip_expense.receipt_id`
 * directly — the budget is computed purely from the item-level `invoice_item_receipt` junction
 * (`findItemReceiptsForInvoice`) plus the invoice-level one, so an expense-line reference can
 * never add a second count for a receipt already attached at the item level; no new
 * double-counting guard was needed to keep that true (see rk-2-application.md item 7).
 */
class ReceiptBudgetServiceTripTest {

    private val receiptRepository: ReceiptRepository = mock()
    private val service = ReceiptBudgetService(receiptRepository)

    private fun receipt(id: Long): Receipt = aReceipt {
        this.id = id
        fileName = "receipt-$id.pdf"
        objectKey = "receipt-pool/receipt-$id.pdf"
        sha256Checksum = "checksum-$id"
        sizeBytes = 1024L
        description = "Beleg $id"
        documentDate = LocalDate.of(2026, 1, 1)
    }

    @Test
    fun `a trip item's own attached receipt counts exactly once in the invoice budget`() {
        val tripItemId = 7L
        val hotelReceipt = receipt(1L)
        whenever(receiptRepository.findForInvoice(1L, 0, Int.MAX_VALUE)).thenReturn(emptyList())
        whenever(receiptRepository.findItemReceiptsForInvoice(1L)).thenReturn(mapOf(tripItemId to listOf(hotelReceipt)))

        val budget = service.invoiceDocumentBudget(1L)

        assertEquals(1, budget.distinctReceiptCount, "the trip's expense line referencing the same receipt does not add a second count")
    }

    @Test
    fun `the trip item's receipt is not double-counted alongside an unrelated invoice-level receipt`() {
        val tripItemId = 7L
        val hotelReceipt = receipt(1L)
        val invoiceLevelReceipt = receipt(2L)
        whenever(receiptRepository.findForInvoice(1L, 0, Int.MAX_VALUE)).thenReturn(listOf(invoiceLevelReceipt))
        whenever(receiptRepository.findItemReceiptsForInvoice(1L)).thenReturn(mapOf(tripItemId to listOf(hotelReceipt)))

        val budget = service.invoiceDocumentBudget(1L)

        assertEquals(2, budget.distinctReceiptCount)
    }
}
