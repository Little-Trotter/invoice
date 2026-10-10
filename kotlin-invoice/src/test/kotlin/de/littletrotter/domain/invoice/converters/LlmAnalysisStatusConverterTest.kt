package de.littletrotter.domain.invoice.converters

import de.littletrotter.domain.invoice.LlmAnalysisStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LlmAnalysisStatusConverterTest {
    private val conv = LlmAnalysisStatusConverter()

    @Test
    fun `null handling`() {
        assertNull(conv.convertToDatabaseColumn(null))
        assertNull(conv.convertToEntityAttribute(null))
    }

    @Test
    fun `roundtrip and out of range`() {
        val db = conv.convertToDatabaseColumn(LlmAnalysisStatus.PENDING)
        assertEquals(LlmAnalysisStatus.PENDING.ordinal, db)
        assertEquals(LlmAnalysisStatus.PENDING, conv.convertToEntityAttribute(db))
        assertThrows<IllegalStateException> { conv.convertToEntityAttribute(999) }
    }
}
