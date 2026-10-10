package de.littletrotter.ui.view.invoice

import de.littletrotter.application.dto.InvoiceExportFilter
import de.littletrotter.application.dto.InvoiceOverviewSortField
import de.littletrotter.application.dto.SortDirection
import de.littletrotter.application.invoice.port.inbound.InvoiceListPage
import de.littletrotter.application.invoice.port.inbound.InvoiceListQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** [loadInvoiceList]: the query half of a list refresh, free of components. */
class InvoiceListRefreshTest {
    private val port: InvoiceListQueryPort = mock()
    private val filter = InvoiceExportFilter(filterText = "acme")

    private fun query(pageIndex: Int, pageSize: Int = 10) = InvoiceListQuery(
        filter, pageIndex, pageSize, InvoiceOverviewSortField.INVOICE_DATE, SortDirection.DESC, InvoiceStatus.SENT,
    )

    @Test
    fun `happy path - counts, page and status chips come from the same filter`() {
        whenever(port.count(filter)).thenReturn(25L)
        whenever(port.findPage(any(), any())).thenReturn(emptyList())
        whenever(port.countByStatus(filter)).thenReturn(mapOf(InvoiceStatus.SENT to 3L))

        val load = loadInvoiceList(port, query(pageIndex = 1))

        assertEquals(3, load.totalPages)
        assertEquals(1, load.pageIndex)
        assertEquals(25L, load.total)
        assertEquals(mapOf(InvoiceStatus.SENT to 3L), load.statusCounts)
    }

    @Test
    fun `boundary - a page index past the shrunken result is clamped to the last page`() {
        whenever(port.count(filter)).thenReturn(11L)
        whenever(port.findPage(any(), any())).thenReturn(emptyList())
        whenever(port.countByStatus(filter)).thenReturn(emptyMap())

        val load = loadInvoiceList(port, query(pageIndex = 7))

        assertEquals(1, load.pageIndex)
        val page = argumentCaptor<InvoiceListPage>()
        verify(port).findPage(any(), page.capture())
        assertEquals(1, page.firstValue.pageIndex)
    }

    @Test
    fun `boundary - an empty result still has one page at index zero`() {
        whenever(port.count(filter)).thenReturn(0L)
        whenever(port.findPage(any(), any())).thenReturn(emptyList())
        whenever(port.countByStatus(filter)).thenReturn(emptyMap())

        val load = loadInvoiceList(port, query(pageIndex = 3))

        assertEquals(1, load.totalPages)
        assertEquals(0, load.pageIndex)
    }
}
