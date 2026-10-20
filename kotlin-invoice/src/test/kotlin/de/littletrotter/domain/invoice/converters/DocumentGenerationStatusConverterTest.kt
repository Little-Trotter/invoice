package de.littletrotter.domain.invoice.converters

import de.littletrotter.domain.invoice.DocumentGenerationStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DocumentGenerationStatusConverterTest {
    private val conv = DocumentGenerationStatusConverter()

    @Test
    fun `null handling`() {
        assertNull(conv.convertToDatabaseColumn(null))
        assertNull(conv.convertToEntityAttribute(null))
    }

    @Test
    fun `roundtrip and out of range`() {
        val db = conv.convertToDatabaseColumn(DocumentGenerationStatus.PENDING)
        assertEquals(DocumentGenerationStatus.PENDING.ordinal, db)
        assertEquals(DocumentGenerationStatus.PENDING, conv.convertToEntityAttribute(db))
        assertThrows<IllegalStateException> { conv.convertToEntityAttribute(999) }
    }
}
