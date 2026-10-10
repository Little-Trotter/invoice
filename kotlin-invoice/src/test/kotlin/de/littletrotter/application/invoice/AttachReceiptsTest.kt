package de.littletrotter.application.invoice

import de.littletrotter.application.dto.ReceiptDetachSelection
import de.littletrotter.application.dto.ReceiptRowDto
import de.littletrotter.domain.masterdata.Receipt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.OffsetDateTime

/** [AttachReceipts] on top of [ReceiptUploadService]'s and [ReceiptLinkService]'s
 *  existing writes. */
class AttachReceiptsTest {

    private val receiptUploadService: ReceiptUploadService = mock()
    private val receiptLinkService: ReceiptLinkService = mock()
    private val useCase = AttachReceipts(receiptUploadService, receiptLinkService)

    private fun persistedReceipt(id: Long = 1L): Receipt = Receipt().apply {
        this.id = id
        fileName = "a.pdf"
        description = "desc"
        documentDate = LocalDate.of(2026, 9, 1)
        sizeBytes = 3L
        uploadedAt = OffsetDateTime.now()
        objectKey = "obj-$id"
    }

    @Test
    fun `happy path - upload forwards all fields and returns the persisted receipt as a DTO`() {
        val receipt = persistedReceipt()
        val documentDate = LocalDate.of(2026, 9, 1)
        val bytes = byteArrayOf(1, 2, 3)
        whenever(receiptUploadService.upload("a.pdf", "desc", bytes, documentDate)).thenReturn(receipt)

        val expected = ReceiptRowDto(
            id = receipt.id!!,
            fileName = receipt.fileName,
            description = receipt.description,
            documentDate = receipt.documentDate,
            sizeBytes = receipt.sizeBytes,
            uploadedAt = receipt.uploadedAt,
            objectKey = receipt.objectKey,
        )
        assertEquals(expected, useCase.upload("a.pdf", "desc", bytes, documentDate))
    }

    @Test
    fun `boundary - upload forwards a null fileName and null documentDate unchanged`() {
        val receipt = persistedReceipt(id = 2L).apply { documentDate = null }
        val bytes = byteArrayOf(4)
        whenever(receiptUploadService.upload(null, "desc", bytes, null)).thenReturn(receipt)

        assertEquals(receipt.id, useCase.upload(null, "desc", bytes, null).id)
    }

    @Test
    fun `boundary - addReceiptsToInvoice omits expectedVersion by default`() {
        useCase.addReceiptsToInvoice(1L, listOf(10L, 11L))

        verify(receiptLinkService).addReceiptsToInvoice(1L, listOf(10L, 11L), null)
    }

    @Test
    fun `boundary - addReceiptsToItem forwards an explicit expectedVersion`() {
        useCase.addReceiptsToItem(2L, listOf(20L), 7L)

        verify(receiptLinkService).addReceiptsToItem(2L, listOf(20L), 7L)
    }

    @Test
    fun `boundary - detachReceipts forwards the selection unchanged`() {
        val selection = ReceiptDetachSelection(invoiceLevelReceiptIds = setOf(30L), itemReceiptIds = mapOf(40L to setOf(41L)))

        useCase.detachReceipts(3L, selection, 9L)

        verify(receiptLinkService).detachReceipts(3L, selection, 9L)
    }
}
