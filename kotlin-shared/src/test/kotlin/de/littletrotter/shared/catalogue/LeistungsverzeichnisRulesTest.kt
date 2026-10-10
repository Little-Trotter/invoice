package de.littletrotter.shared.catalogue

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue

class LeistungsverzeichnisRulesTest {
    @Test
    fun `material permits exactly the requested catalogue units`() {
        assertEquals(
            setOf(
                InvoiceItemUnit.PIECE,
                InvoiceItemUnit.METER,
                InvoiceItemUnit.SQUARE_METER,
                InvoiceItemUnit.CUBIC_METER,
                InvoiceItemUnit.KILOMETER,
                InvoiceItemUnit.LITER,
                InvoiceItemUnit.KILOGRAM,
                InvoiceItemUnit.APPROACH,
                InvoiceItemUnit.GRAM,
                InvoiceItemUnit.LINEAR_METER,
            ),
            LeistungsverzeichnisRules.allowedUnits(InvoiceItemCategory.PRODUCT),
        )
    }

    @Test
    fun `material excludes units outside the explicit material list`() {
        for (unit in InvoiceItemUnit.entries) {
            assertEquals(
                unit in LeistungsverzeichnisRules.allowedUnits(InvoiceItemCategory.PRODUCT),
                LeistungsverzeichnisRules.isAllowed(InvoiceItemCategory.PRODUCT, unit),
            )
        }
        assertTrue(LeistungsverzeichnisRules.isAllowed(InvoiceItemCategory.SERVICE, InvoiceItemUnit.HOUR))
    }

    @Test
    fun `unit price accepts physical dimensions and lump sum only`() {
        assertTrue(LeistungsverzeichnisRules.isAllowed(InvoiceItemCategory.UNIT_PRICE, InvoiceItemUnit.SQUARE_METER))
        assertTrue(LeistungsverzeichnisRules.isAllowed(InvoiceItemCategory.UNIT_PRICE, InvoiceItemUnit.CUBIC_METER))
        assertTrue(LeistungsverzeichnisRules.isAllowed(InvoiceItemCategory.UNIT_PRICE, InvoiceItemUnit.FLAT_RATE))
        assertFalse(LeistungsverzeichnisRules.isAllowed(InvoiceItemCategory.UNIT_PRICE, InvoiceItemUnit.HOUR))
        assertFalse(LeistungsverzeichnisRules.isAllowed(InvoiceItemCategory.UNIT_PRICE, InvoiceItemUnit.PIECE))
        assertFalse(LeistungsverzeichnisRules.isAllowed(InvoiceItemCategory.UNIT_PRICE, InvoiceItemUnit.LITER))
    }

    @Test
    fun `service and trip keep their restricted units`() {
        assertTrue(LeistungsverzeichnisRules.isAllowed(InvoiceItemCategory.SERVICE, InvoiceItemUnit.DAY))
        assertTrue(LeistungsverzeichnisRules.isAllowed(InvoiceItemCategory.SERVICE, InvoiceItemUnit.APPROACH))
        assertFalse(LeistungsverzeichnisRules.isAllowed(InvoiceItemCategory.SERVICE, InvoiceItemUnit.PIECE))
        assertFalse(LeistungsverzeichnisRules.isAllowed(InvoiceItemCategory.SERVICE, InvoiceItemUnit.METER))
        assertTrue(LeistungsverzeichnisRules.isAllowed(InvoiceItemCategory.TRIP, InvoiceItemUnit.FLAT_RATE))
        assertFalse(LeistungsverzeichnisRules.isAllowed(InvoiceItemCategory.TRIP, InvoiceItemUnit.DAY))
    }

    @Test
    fun `shared validator rejects a unit outside the selected category`() {
        assertThrows(IllegalArgumentException::class.java) {
            LeistungsverzeichnisRules.requireAllowed(InvoiceItemCategory.UNIT_PRICE, InvoiceItemUnit.HOUR)
        }
        LeistungsverzeichnisRules.requireAllowed(InvoiceItemCategory.UNIT_PRICE, InvoiceItemUnit.SQUARE_METER)
    }
}
