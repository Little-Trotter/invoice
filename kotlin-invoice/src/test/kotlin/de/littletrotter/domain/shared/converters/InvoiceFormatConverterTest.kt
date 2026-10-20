package de.littletrotter.domain.shared.converters

import de.littletrotter.domain.shared.InvoiceFormat
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class InvoiceFormatConverterTest {
    private val conv = InvoiceFormatConverter()

    @Test
    fun `null handling`() {
        assertNull(conv.convertToDatabaseColumn(null))
        assertNull(conv.convertToEntityAttribute(null))
    }

    @Test
    fun `roundtrip all constants`() {
        InvoiceFormat.entries.forEach { constant ->
            val dbValue = conv.convertToDatabaseColumn(constant)
            assertEquals(constant.ordinal, dbValue)
            assertEquals(constant, conv.convertToEntityAttribute(dbValue))
        }
    }

    @Test
    fun `boundary first and last ordinals`() {
        val first = InvoiceFormat.entries.first()
        assertEquals(first.ordinal, conv.convertToDatabaseColumn(first))
        assertEquals(first, conv.convertToEntityAttribute(first.ordinal))

        val last = InvoiceFormat.entries.last()
        assertEquals(last.ordinal, conv.convertToDatabaseColumn(last))
        assertEquals(last, conv.convertToEntityAttribute(last.ordinal))
    }

    @Test
    fun `unknown ordinal beyond last constant fails loudly`() {
        assertThrows<IllegalStateException> { conv.convertToEntityAttribute(InvoiceFormat.entries.size) }
        assertThrows<IllegalStateException> { conv.convertToEntityAttribute(InvoiceFormat.entries.size + 100) }
    }
}
