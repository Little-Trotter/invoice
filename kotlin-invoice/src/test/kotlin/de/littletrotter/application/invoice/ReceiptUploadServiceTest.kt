package de.littletrotter.application.invoice

import de.littletrotter.domain.masterdata.Receipt
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.shared.DocumentStore
import de.littletrotter.test.FIXED_TIME_SOURCE
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDate

/**
 * The order of effects in [ReceiptUploadService.upload]: the object is written before the
 * transaction opens, and the transaction only verifies it under the checksum lock.
 */
class ReceiptUploadServiceTest {

    private val receiptRepository: ReceiptRepository = mock()
    private val documentStore: DocumentStore = mock()
    private val service = receiptUploadService(receiptRepository, documentStore, time = FIXED_TIME_SOURCE)
    private val pdf = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x37)
    private val checksum = receiptSha256Hex(pdf)

    init {
        whenever(receiptRepository.saveAndFlush(any())).thenAnswer { it.getArgument<Receipt>(0) }
    }

    @Test
    fun `object is written before the lock, then verified under the lock, then the row is inserted`() {
        whenever(documentStore.exists(any())).thenReturn(true)

        service.upload("a.pdf", "A", pdf, LocalDate.of(2026, 3, 14))

        val order = inOrder(documentStore, receiptRepository)
        order.verify(documentStore).put(any(), eq(pdf), eq("application/pdf"))
        order.verify(receiptRepository).lockChecksum(checksum)
        order.verify(documentStore).exists(any())
        order.verify(receiptRepository).saveAndFlush(any())
        verify(documentStore, times(1)).put(any(), any(), any())
    }

    @Test
    fun `a delete that removed the object before the lock makes the transaction write it once more`() {
        whenever(documentStore.exists(any())).thenReturn(false)

        service.upload("a.pdf", "A", pdf, LocalDate.of(2026, 3, 14))

        val order = inOrder(documentStore, receiptRepository)
        order.verify(documentStore).put(any(), any(), any())
        order.verify(receiptRepository).lockChecksum(checksum)
        order.verify(documentStore).exists(any())
        order.verify(documentStore).put(any(), any(), any())
        order.verify(receiptRepository).saveAndFlush(any())
        verify(documentStore, times(2)).put(any(), any(), any())
    }
}
