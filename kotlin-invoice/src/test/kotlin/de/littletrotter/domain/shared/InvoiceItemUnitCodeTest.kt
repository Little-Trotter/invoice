package de.littletrotter.domain.shared

import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class InvoiceItemUnitCodeTest {

    @Test
    fun `InvoiceItem unitCode is derived from unit for every value, never null or the invalid ST fallback`() {
        InvoiceItemUnit.entries.forEach { u ->
            val item = InvoiceItem().apply { unit = u }
            assertEquals(u.uneceCode, item.unitCode)
            assertNotEquals("ST", item.unitCode)
        }
    }
}
