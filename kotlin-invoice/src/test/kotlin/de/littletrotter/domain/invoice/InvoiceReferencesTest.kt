package de.littletrotter.domain.invoice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate

class InvoiceReferencesTest {

    @Test
    fun `a new InvoiceReferences has all fields null`() {
        val refs = InvoiceReferences()

        assertNull(refs.buyerReference)
        assertNull(refs.purchaseOrderReference)
        assertNull(refs.orderReference)
        assertNull(refs.orderReferenceDate)
        assertNull(refs.offerReference)
        assertNull(refs.offerReferenceDate)
        assertNull(refs.projectNumber)
        assertNull(refs.costCenter)
    }

    @Test
    fun `copyFrom() copies all reference fields from another InvoiceReferences`() {
        val source = InvoiceReferences().apply {
            buyerReference = "BT-10-value"
            purchaseOrderReference = "BT-13-value"
            orderReference = "BT-15-value"
            orderReferenceDate = LocalDate.of(2026, 9, 17)
            offerReference = "BT-17-value"
            offerReferenceDate = LocalDate.of(2026, 9, 15)
            projectNumber = "BT-11-value"
            costCenter = "CC-001"
        }

        val target = InvoiceReferences()
        target.copyFrom(source)

        assertEquals("BT-10-value", target.buyerReference)
        assertEquals("BT-13-value", target.purchaseOrderReference)
        assertEquals("BT-15-value", target.orderReference)
        assertEquals(LocalDate.of(2026, 9, 17), target.orderReferenceDate)
        assertEquals("BT-17-value", target.offerReference)
        assertEquals(LocalDate.of(2026, 9, 15), target.offerReferenceDate)
        assertEquals("BT-11-value", target.projectNumber)
        assertEquals("CC-001", target.costCenter)
    }

    @Test
    fun `copyFrom() handles partial fields correctly`() {
        val source = InvoiceReferences().apply {
            buyerReference = "BT-10-value"
            projectNumber = "BT-11-value"
            // other fields remain null
        }

        val target = InvoiceReferences()
        target.copyFrom(source)

        assertEquals("BT-10-value", target.buyerReference)
        assertEquals("BT-11-value", target.projectNumber)
        assertNull(target.purchaseOrderReference)
        assertNull(target.orderReference)
        assertNull(target.offerReference)
        assertNull(target.costCenter)
    }

    @Test
    fun `copyFrom() overwrites existing values in the target`() {
        val target = InvoiceReferences().apply {
            buyerReference = "OLD-VALUE"
            costCenter = "OLD-CC"
        }

        val source = InvoiceReferences().apply {
            buyerReference = "NEW-VALUE"
            costCenter = null
        }

        target.copyFrom(source)

        assertEquals("NEW-VALUE", target.buyerReference)
        assertNull(target.costCenter)
    }

    @Test
    fun `copyFrom() with all null fields results in all null target fields`() {
        val target = InvoiceReferences().apply {
            buyerReference = "OLD-VALUE"
            projectNumber = "OLD-PN"
        }

        val source = InvoiceReferences() // all null
        target.copyFrom(source)

        assertNull(target.buyerReference)
        assertNull(target.projectNumber)
        assertNull(target.purchaseOrderReference)
        assertNull(target.orderReference)
        assertNull(target.offerReference)
        assertNull(target.costCenter)
    }

    @Test
    fun `copyFrom() preserves date fields correctly`() {
        val today = LocalDate.of(2026, 9, 17)
        val tomorrow = LocalDate.of(2026, 9, 18)

        val source = InvoiceReferences().apply {
            orderReferenceDate = today
            offerReferenceDate = tomorrow
        }

        val target = InvoiceReferences()
        target.copyFrom(source)

        assertEquals(today, target.orderReferenceDate)
        assertEquals(tomorrow, target.offerReferenceDate)
    }

    @Test
    fun `copyFrom() can be called multiple times to update references`() {
        val refs = InvoiceReferences()

        val source1 = InvoiceReferences().apply {
            buyerReference = "FIRST"
        }
        refs.copyFrom(source1)
        assertEquals("FIRST", refs.buyerReference)

        val source2 = InvoiceReferences().apply {
            buyerReference = "SECOND"
            projectNumber = "PN-001"
        }
        refs.copyFrom(source2)
        assertEquals("SECOND", refs.buyerReference)
        assertEquals("PN-001", refs.projectNumber)
    }
}
