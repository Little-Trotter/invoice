package de.littletrotter.application.invoice

import de.littletrotter.application.dto.ReceiptRowDto
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.fixture.aReceipt
import de.littletrotter.shared.receipt.InvoiceDocumentBudget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.OffsetDateTime

/** [InvoiceReceiptQueries] on top of [ReceiptLinkService]'s, [ReceiptBudgetService]'s
 *  and [ReceiptPoolService]'s existing reads, mapped to [ReceiptRowDto]. */
class InvoiceReceiptQueriesTest {

    private val receiptLinkService: ReceiptLinkService = mock()
    private val receiptBudgetService: ReceiptBudgetService = mock()
    private val receiptPoolService: ReceiptPoolService = mock()
    private val queries = InvoiceReceiptQueries(receiptLinkService, receiptBudgetService, receiptPoolService)

    private fun persistedReceipt(id: Long) = aReceipt {
        this.id = id
        fileName = "r$id.pdf"
        description = "desc-$id"
        documentDate = LocalDate.of(2026, 1, 1)
        sizeBytes = 100L
        uploadedAt = OffsetDateTime.now()
        objectKey = "obj-$id"
    }

    private fun expectedRowDto(receipt: Receipt) = ReceiptRowDto(
        id = receipt.id!!,
        fileName = receipt.fileName,
        description = receipt.description,
        documentDate = receipt.documentDate,
        sizeBytes = receipt.sizeBytes,
        uploadedAt = receipt.uploadedAt,
        objectKey = receipt.objectKey,
    )

    @Test
    fun `happy path - findReceiptsForInvoice forwards to the link service, mapped to DTOs`() {
        val receipt = persistedReceipt(1L)
        whenever(receiptLinkService.findReceiptsForInvoice(1L)).thenReturn(listOf(receipt))

        assertEquals(listOf(expectedRowDto(receipt)), queries.findReceiptsForInvoice(1L))
    }

    @Test
    fun `boundary - findItemReceiptsForInvoice returns the per-item map, mapped to DTOs`() {
        val receipt = persistedReceipt(2L)
        whenever(receiptLinkService.findItemReceiptsForInvoice(2L)).thenReturn(mapOf(10L to listOf(receipt)))

        assertEquals(mapOf(10L to listOf(expectedRowDto(receipt))), queries.findItemReceiptsForInvoice(2L))
    }

    @Test
    fun `boundary - findReceiptsForItem forwards to the link service, mapped to DTOs`() {
        val receipt = persistedReceipt(3L)
        whenever(receiptLinkService.findReceiptsForItem(3L)).thenReturn(listOf(receipt))

        assertEquals(listOf(expectedRowDto(receipt)), queries.findReceiptsForItem(3L))
    }

    @Test
    fun `boundary - countReceiptsForItem forwards to the link service`() {
        whenever(receiptLinkService.countReceiptsForItem(4L)).thenReturn(2L)

        assertEquals(2L, queries.countReceiptsForItem(4L))
    }

    @Test
    fun `boundary - invoiceDocumentBudget forwards to the budget service`() {
        val budget = mock<InvoiceDocumentBudget>()
        whenever(receiptBudgetService.invoiceDocumentBudget(5L)).thenReturn(budget)

        assertEquals(budget, queries.invoiceDocumentBudget(5L))
    }

    @Test
    fun `boundary - getBytes forwards to the pool service`() {
        val bytes = byteArrayOf(1, 2, 3)
        whenever(receiptPoolService.getBytes(6L)).thenReturn(bytes)

        assertEquals(bytes, queries.getBytes(6L))
    }

    @Test
    fun `findAssignableForItem forwards the paging and exclusion arguments, mapped to DTOs`() {
        val receipt = persistedReceipt(7L)
        whenever(receiptLinkService.findAssignableForItem(8L, 1, 25, "rechnung", setOf(3L))).thenReturn(listOf(receipt))

        assertEquals(listOf(expectedRowDto(receipt)), queries.findAssignableForItem(8L, 1, 25, "rechnung", setOf(3L)))
    }

    @Test
    fun `countAssignableForItem forwards filter and exclusions`() {
        whenever(receiptLinkService.countAssignableForItem(8L, "x", setOf(1L, 2L))).thenReturn(4L)

        assertEquals(4L, queries.countAssignableForItem(8L, "x", setOf(1L, 2L)))
    }

    @Test
    fun `findAssignableForInvoice forwards paging and filter, mapped to DTOs`() {
        val receipt = persistedReceipt(9L)
        whenever(receiptLinkService.findAssignableForInvoice(null, 0, 60, null)).thenReturn(listOf(receipt))

        assertEquals(listOf(expectedRowDto(receipt)), queries.findAssignableForInvoice(null, 0, 60, null))
    }

    @Test
    fun `countAssignableForInvoice forwards the filter`() {
        whenever(receiptLinkService.countAssignableForInvoice(10L, "f")).thenReturn(3L)

        assertEquals(3L, queries.countAssignableForInvoice(10L, "f"))
    }

    @Test
    fun `findUsageSummaries forwards ids and both labels`() {
        whenever(receiptPoolService.findUsageSummaries(listOf(1L, 2L), "unbenutzt", "Rechnung")).thenReturn(mapOf(1L to "Rechnung"))

        assertEquals(mapOf(1L to "Rechnung"), queries.findUsageSummaries(listOf(1L, 2L), "unbenutzt", "Rechnung"))
    }

    @Test
    fun `a receipt without id cannot be projected to a row`() {
        whenever(receiptLinkService.findReceiptsForItem(11L)).thenReturn(listOf(Receipt()))

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) { queries.findReceiptsForItem(11L) }
    }
}
