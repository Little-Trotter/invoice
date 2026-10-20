package de.littletrotter.application.invoice.port.inbound

import de.littletrotter.application.dto.ReceiptDetachSelection
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

/** Default arguments of the receipt and item-entry ports: callers rely on them for "no filter, first page". */
class InvoicePortDefaultsTest {

    @Test
    fun `receipt pool search without filter or exclusions passes null and an empty set`() {
        val port: InvoiceReceiptQueryPort = mock()

        port.findAssignableForItem(5L, 0, 20)
        port.countAssignableForItem(5L)

        verify(port).findAssignableForItem(5L, 0, 20, null, emptySet())
        verify(port).countAssignableForItem(5L, null, emptySet())
    }

    @Test
    fun `invoice-level pool search without filter passes a null filter`() {
        val port: InvoiceReceiptQueryPort = mock()

        port.findAssignableForInvoice(7L, 1, 10)
        port.countAssignableForInvoice(7L)

        verify(port).findAssignableForInvoice(7L, 1, 10, null)
        verify(port).countAssignableForInvoice(7L, null)
    }

    @Test
    fun `detaching receipts without a version does not assert an expected version`() {
        val port: AttachReceiptsUseCase = mock()
        val selection = ReceiptDetachSelection(invoiceLevelReceiptIds = setOf(1L))

        port.detachReceipts(3L, selection)

        verify(port).detachReceipts(3L, selection, null)
    }

    @Test
    fun `article select without filter asks for the first 60 articles`() {
        val port: ItemEntryQueryPort = mock()

        port.countArticlesForSelect(InvoiceItemCategory.SERVICE)
        port.findArticlesForSelect(InvoiceItemCategory.PRODUCT)

        verify(port).countArticlesForSelect(InvoiceItemCategory.SERVICE, null)
        verify(port).findArticlesForSelect(InvoiceItemCategory.PRODUCT, null, 0, 60)
    }
}
