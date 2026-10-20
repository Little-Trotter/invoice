package de.littletrotter.domain.invoice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** [InvoiceReferences.update], [InvoiceReferences.assignBuyerReference] and the change round trip. */
class InvoiceReferenceMethodsTest {

    @Test
    fun `update replaces all eight reference fields`() {
        val refs = InvoiceReferences()

        refs.update(
            InvoiceReferencesChange(
                buyerReference = "BT-10",
                purchaseOrderReference = "BT-13",
                orderReference = "BT-15",
                orderReferenceDate = LocalDate.of(2026, 4, 1),
                offerReference = "BT-17",
                offerReferenceDate = LocalDate.of(2026, 3, 1),
                projectNumber = "BT-11",
                costCenter = "CC-1",
            ),
        )

        assertEquals("BT-10", refs.buyerReference)
        assertEquals("BT-13", refs.purchaseOrderReference)
        assertEquals("BT-15", refs.orderReference)
        assertEquals(LocalDate.of(2026, 4, 1), refs.orderReferenceDate)
        assertEquals("BT-17", refs.offerReference)
        assertEquals(LocalDate.of(2026, 3, 1), refs.offerReferenceDate)
        assertEquals("BT-11", refs.projectNumber)
        assertEquals("CC-1", refs.costCenter)
    }

    @Test
    fun `update with an empty change clears previously set fields`() {
        val refs = InvoiceReferences()
        refs.update(InvoiceReferencesChange(buyerReference = "BT-10", costCenter = "CC-1"))

        refs.update(InvoiceReferencesChange())

        assertNull(refs.buyerReference)
        assertNull(refs.costCenter)
    }

    @Test
    fun `assignBuyerReference touches only the buyer reference`() {
        val refs = InvoiceReferences()
        refs.update(InvoiceReferencesChange(buyerReference = "OLD", projectNumber = "P-1"))

        refs.assignBuyerReference("NEW")

        assertEquals("NEW", refs.buyerReference)
        assertEquals("P-1", refs.projectNumber)
    }

    @Test
    fun `assignBuyerReference with null clears the buyer reference`() {
        val refs = InvoiceReferences()
        refs.assignBuyerReference("BT-10")

        refs.assignBuyerReference(null)

        assertNull(refs.buyerReference)
    }

    @Test
    fun `asChange is the inverse of update`() {
        val change = InvoiceReferencesChange(
            buyerReference = "BT-10",
            orderReferenceDate = LocalDate.of(2026, 4, 1),
            costCenter = "CC-1",
        )
        val refs = InvoiceReferences()
        refs.update(change)

        assertEquals(change, refs.asChange())
    }
}
