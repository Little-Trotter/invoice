package de.littletrotter.application.invoice

import de.littletrotter.domain.masterdata.Receipt
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDate

class QuoteInvoiceReceiptsTest {

    private val uploads: ReceiptUploadService = mock()
    private val links: ReceiptLinkService = mock()
    private val receipts = QuoteInvoiceReceipts(uploads, links)
    private val date = LocalDate.of(2026, 10, 1)

    private fun stored(id: Long) = Receipt().also { it.id = id }

    @Test
    fun `every source is uploaded and all of them are linked in one call`() {
        val first = byteArrayOf(1)
        val second = byteArrayOf(2)
        whenever(uploads.upload("a.pdf", "A", first, date)).thenReturn(stored(10))
        whenever(uploads.upload("b.pdf", "B", second, date)).thenReturn(stored(11))

        receipts.attach(5L, listOf(QuoteReceiptSource("a.pdf", "A", date, first), QuoteReceiptSource("b.pdf", "B", date, second)))

        verify(links).addReceiptsToInvoice(5L, listOf(10L, 11L))
    }

    @Test
    fun `no sources means no upload and no link`() {
        receipts.attach(5L, emptyList())

        verify(uploads, never()).upload(any(), any(), any(), any(), any())
        verify(links, never()).addReceiptsToInvoice(any(), any(), any())
    }
}
