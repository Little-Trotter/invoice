package de.littletrotter.application

import de.littletrotter.application.error.ApplicationNotFoundException
import de.littletrotter.application.error.ApplicationValidationException
import de.littletrotter.shared.DocumentStore
import de.littletrotter.application.invoice.ReceiptLinkService
import de.littletrotter.application.invoice.ReceiptPoolService
import de.littletrotter.application.invoice.ReceiptUploadService
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.ReceiptRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDate
import de.littletrotter.test.FIXED_TIME_SOURCE

class ReceiptServiceTranslationTest {

    @Test
    fun `upload translates invalid pdf to application validation`() {
        val service = ReceiptUploadService(mock<ReceiptRepository>(), mock<DocumentStore>(), time = FIXED_TIME_SOURCE)

        val exception = assertThrows(ApplicationValidationException::class.java) {
            service.upload("x.pdf", "Beleg x.pdf", byteArrayOf(0x00, 0x01), LocalDate.of(2026, 3, 14))
        }

        assertEquals("Only PDF documents are allowed.", exception.message)
    }

    @Test
    fun `findById translates missing receipt to application not found`() {
        val repository = mock<ReceiptRepository>()
        whenever(repository.findById(41L)).thenReturn(null)
        val service = ReceiptPoolService(repository, mock<DocumentStore>())

        assertThrows(ApplicationNotFoundException::class.java) {
            service.findById(41L)
        }
    }

    @Test
    fun `countReceiptsForItem translates missing item without touching receipt repository`() {
        val receiptRepository = mock<ReceiptRepository>()
        val invoiceRepository = mock<InvoiceRepository>()
        whenever(invoiceRepository.findInvoiceByItemId(7L)).thenReturn(null)
        val service = ReceiptLinkService(receiptRepository, invoiceRepository, mock(), mock())

        val exception = assertThrows(ApplicationValidationException::class.java) {
            service.countReceiptsForItem(7L)
        }

        assertEquals("Invoice item not found: 7", exception.message)
        verify(receiptRepository, never()).countForItem(7L)
    }
}
