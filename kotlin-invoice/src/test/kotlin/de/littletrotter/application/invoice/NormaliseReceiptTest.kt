package de.littletrotter.application.invoice

import de.littletrotter.application.error.ApplicationNotFoundException
import de.littletrotter.application.invoice.port.outbound.ReceiptPdfReducer
import de.littletrotter.application.invoice.port.outbound.ReceiptReduction
import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.masterdata.ReceiptNormalisationStage
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.receipt.DocumentSize
import de.littletrotter.shared.receipt.ReceiptSizePolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.OffsetDateTime

/** [NormaliseReceipt]: reducing an already-stored receipt in place, mirroring [ReceiptUploadService
 *  .upload]'s own checksum/collision handling for the "this row already exists" case. */
class NormaliseReceiptTest {

    private val receiptRepository: ReceiptRepository = mock()
    private val documentStore: DocumentStore = mock()
    private val receiptPdfReducer: ReceiptPdfReducer = mock()
    private val useCase = NormaliseReceipt(receiptRepository, documentStore, receiptPdfReducer)

    private fun persistedReceipt(
        id: Long = 1L,
        sizeBytes: Long = 500L,
        checksum: String = "checksum-$id",
        originalSizeBytes: Long? = null,
    ) = Receipt().apply {
        this.id = id
        fileName = "r$id.pdf"
        description = "desc"
        documentDate = LocalDate.of(2026, 1, 1)
        this.sizeBytes = sizeBytes
        sha256Checksum = checksum
        uploadedAt = OffsetDateTime.now()
        objectKey = "receipt-pool/$checksum.pdf"
        normalisationStage = ReceiptNormalisationStage.NONE
        this.originalSizeBytes = originalSizeBytes
    }

    @Test
    fun `normalise leaves an already-small receipt unchanged and does not touch the store`() {
        val receipt = persistedReceipt()
        val storedBytes = byteArrayOf(1, 2, 3)
        whenever(receiptRepository.findById(1L)).thenReturn(receipt)
        whenever(documentStore.get(receipt.objectKey)).thenReturn(storedBytes)
        whenever(receiptPdfReducer.reduce(storedBytes, ReceiptSizePolicy.TARGET_RECEIPT_SIZE))
            .thenReturn(ReceiptReduction.Reduced(storedBytes, ReceiptNormalisationStage.NONE, dpi = 0))

        val result = useCase.normalise(1L)

        assertEquals(ReceiptNormalisationStage.NONE, result.normalisationStage)
        assertEquals(500L, result.sizeBytes)
        assertEquals(storedBytes.size.toLong(), receipt.originalSizeBytes, "originalSizeBytes is backfilled even when nothing else changes")
        verify(documentStore, never()).put(any(), any(), any())
        verify(receiptRepository, never()).saveAndFlush(any())
    }

    @Test
    fun `normalise overwrites the stored object and persists the reduced size, checksum and stage`() {
        val receipt = persistedReceipt(originalSizeBytes = 500L)
        val storedBytes = byteArrayOf(9, 9, 9)
        val reducedBytes = byteArrayOf(1)
        whenever(receiptRepository.findById(1L)).thenReturn(receipt)
        whenever(documentStore.get(receipt.objectKey)).thenReturn(storedBytes)
        whenever(receiptPdfReducer.reduce(storedBytes, ReceiptSizePolicy.TARGET_RECEIPT_SIZE))
            .thenReturn(ReceiptReduction.Reduced(reducedBytes, ReceiptNormalisationStage.RASTERISED, dpi = 150))
        whenever(receiptRepository.findByChecksum(any())).thenReturn(null)
        whenever(receiptRepository.saveAndFlush(any())).thenAnswer { it.getArgument<Receipt>(0) }

        val result = useCase.normalise(1L)

        assertEquals(ReceiptNormalisationStage.RASTERISED, result.normalisationStage)
        assertEquals(reducedBytes.size.toLong(), result.sizeBytes)
        assertEquals(500L, result.originalSizeBytes, "already set — must not be overwritten by the pre-reduction size")
        verify(documentStore).put(receipt.objectKey, reducedBytes, "application/pdf")
    }

    @Test
    fun `normalise backfills originalSizeBytes when it was missing`() {
        val receipt = persistedReceipt(originalSizeBytes = null)
        val storedBytes = byteArrayOf(9, 9, 9, 9)
        whenever(receiptRepository.findById(1L)).thenReturn(receipt)
        whenever(documentStore.get(receipt.objectKey)).thenReturn(storedBytes)
        whenever(receiptPdfReducer.reduce(storedBytes, ReceiptSizePolicy.TARGET_RECEIPT_SIZE))
            .thenReturn(ReceiptReduction.Reduced(byteArrayOf(1), ReceiptNormalisationStage.IMAGES_DOWNSAMPLED, dpi = 150))
        whenever(receiptRepository.saveAndFlush(any())).thenAnswer { it.getArgument<Receipt>(0) }

        val result = useCase.normalise(1L)

        assertEquals(storedBytes.size.toLong(), result.originalSizeBytes)
    }

    @Test
    fun `normalise propagates a StillTooLarge result as ReceiptReductionFailedException`() {
        val receipt = persistedReceipt()
        val storedBytes = byteArrayOf(1)
        whenever(receiptRepository.findById(1L)).thenReturn(receipt)
        whenever(documentStore.get(receipt.objectKey)).thenReturn(storedBytes)
        whenever(receiptPdfReducer.reduce(storedBytes, ReceiptSizePolicy.TARGET_RECEIPT_SIZE))
            .thenReturn(ReceiptReduction.StillTooLarge(pageCount = 4, resultingSize = DocumentSize.ofBytes(900_000)))

        val ex = assertThrows(ReceiptReductionFailedException::class.java) { useCase.normalise(1L) }
        assertEquals(4, ex.pageCount)
        verify(documentStore, never()).put(any(), any(), any())
    }

    @Test
    fun `normalise rejects a checksum collision with a different receipt and leaves the store untouched`() {
        val receipt = persistedReceipt(id = 1L, checksum = "own-checksum")
        val other = persistedReceipt(id = 2L, checksum = "collision")
        val storedBytes = byteArrayOf(1)
        val reducedBytes = byteArrayOf(2)
        whenever(receiptRepository.findById(1L)).thenReturn(receipt)
        whenever(documentStore.get(receipt.objectKey)).thenReturn(storedBytes)
        whenever(receiptPdfReducer.reduce(storedBytes, ReceiptSizePolicy.TARGET_RECEIPT_SIZE))
            .thenReturn(ReceiptReduction.Reduced(reducedBytes, ReceiptNormalisationStage.RASTERISED, dpi = 150))
        whenever(receiptRepository.findByChecksum(any())).thenReturn(other)

        val ex = assertThrows(ReceiptDuplicateException::class.java) { useCase.normalise(1L) }

        assertEquals(1L, ex.receiptId)
        assertEquals(2L, ex.duplicateOfReceiptId)
        verify(documentStore, never()).put(any(), any(), any())
        verify(receiptRepository, never()).saveAndFlush(any())
    }

    @Test
    fun `normalise throws when the receipt does not exist`() {
        whenever(receiptRepository.findById(99L)).thenReturn(null)

        assertThrows(ApplicationNotFoundException::class.java) { useCase.normalise(99L) }
    }

    @Test
    fun `normalise translates a missing object in the store to a not-found error`() {
        val receipt = persistedReceipt()
        whenever(receiptRepository.findById(1L)).thenReturn(receipt)
        whenever(documentStore.get(receipt.objectKey)).thenThrow(NoSuchElementException("missing"))

        assertThrows(de.littletrotter.domain.exception.ReceiptNotFoundException::class.java) { useCase.normalise(1L) }
        assertNull(receipt.originalSizeBytes, "nothing was read, so nothing was backfilled")
    }
}
