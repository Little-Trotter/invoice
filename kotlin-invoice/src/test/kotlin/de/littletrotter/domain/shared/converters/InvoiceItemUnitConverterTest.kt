package de.littletrotter.domain.shared.converters

import de.littletrotter.shared.catalogue.InvoiceItemUnit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class InvoiceItemUnitConverterTest {
    private val conv = InvoiceItemUnitConverter()

    @Test
    fun `null handling`() {
        assertNull(conv.convertToDatabaseColumn(null))
        assertNull(conv.convertToEntityAttribute(null))
    }

    @Test
    fun `roundtrip and out of range`() {
        val db = conv.convertToDatabaseColumn(InvoiceItemUnit.HOUR)
        assertEquals(InvoiceItemUnit.HOUR.ordinal, db)
        assertEquals(InvoiceItemUnit.HOUR, conv.convertToEntityAttribute(db))
        assertThrows<IllegalStateException> { conv.convertToEntityAttribute(999) }
    }
}
