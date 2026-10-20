package de.littletrotter.domain.invoice

import de.littletrotter.fixture.PersistenceEmulation
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Position edits on a draft whose reference group was hydrated as `null` must not store an
 *  empty reference group as a side effect — that store is what made every such draft dirty. */
class InvoiceItemEditsLeaveReferencesUnstoredTest {

    @Test
    fun `adding, recalculating and removing a position never materialises the reference group`() {
        val invoice = Invoice()
        PersistenceEmulation.hydrateNullReferences(invoice)
        val item = anInvoiceItem(description = "Beratung", unitPriceNet = Money.of("100.00"))

        invoice.addItem(item)
        invoice.recalculateTotals()
        assertNull(invoice.references.buyerReference)
        assertNull(PersistenceEmulation.storedReferences(invoice), "adding a position must not store the reference group")

        invoice.removeItem(item)
        invoice.recalculateTotals()
        assertNull(PersistenceEmulation.storedReferences(invoice), "removing a position must not store the reference group")
    }
}
