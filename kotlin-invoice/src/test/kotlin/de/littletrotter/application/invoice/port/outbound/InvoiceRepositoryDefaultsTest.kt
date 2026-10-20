package de.littletrotter.application.invoice.port.outbound

import de.littletrotter.domain.invoice.Invoice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** The paging defaults (first page, 60 rows) are part of the port contract callers rely on. */
class InvoiceRepositoryDefaultsTest {

    @Test
    fun `lookup by recipient and by sender profile default to the first page of 60`() {
        val repo = mock<InvoiceLookupRepository>()
        val byRecipient = listOf(mock<Invoice>())
        val bySender = listOf(mock<Invoice>(), mock<Invoice>())
        whenever(repo.findByRecipientId(1L, 0, 60)).thenReturn(byRecipient)
        whenever(repo.findBySenderProfileId(2L, 0, 60)).thenReturn(bySender)

        assertEquals(byRecipient, repo.findByRecipientId(1L))
        assertEquals(bySender, repo.findBySenderProfileId(2L))
    }

    @Test
    fun `listing defaults to the first page of 60`() {
        val repo = mock<InvoiceListingRepository>()
        val page = listOf(mock<Invoice>())
        whenever(repo.findAll(0, 60)).thenReturn(page)

        assertEquals(page, repo.findAll())
        verify(repo).findAll(0, 60)
    }

    @Test
    fun `overview count defaults to no text filter and no status filter`() {
        val repo = mock<InvoiceListingRepository>()
        whenever(repo.countForOverview(null, null)).thenReturn(4L)

        assertEquals(4L, repo.countForOverview())
    }
}
