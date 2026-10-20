package de.littletrotter.domain.invoice.converters

import de.littletrotter.domain.invoice.InvoiceStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class InvoiceStatusConverterTest {
    private val conv = InvoiceStatusConverter()

    @Test
    fun `null handling`() {
        assertNull(conv.convertToDatabaseColumn(null))
        assertNull(conv.convertToEntityAttribute(null))
    }

    @Test
    fun `roundtrip and out of range`() {
        val db = conv.convertToDatabaseColumn(InvoiceStatus.ISSUED)
        assertEquals(InvoiceStatus.ISSUED.ordinal, db)
        assertEquals(InvoiceStatus.ISSUED, conv.convertToEntityAttribute(db))
        assertThrows<IllegalStateException> { conv.convertToEntityAttribute(999) }
    }
}
