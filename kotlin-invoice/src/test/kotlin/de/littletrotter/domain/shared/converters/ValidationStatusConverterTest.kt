package de.littletrotter.domain.shared.converters

import de.littletrotter.domain.shared.ValidationStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ValidationStatusConverterTest {
    private val conv = ValidationStatusConverter()

    @Test
    fun `null handling`() {
        assertNull(conv.convertToDatabaseColumn(null))
        assertNull(conv.convertToEntityAttribute(null))
    }

    @Test
    fun `roundtrip and out of range`() {
        val db = conv.convertToDatabaseColumn(ValidationStatus.INVALID)
        assertEquals(ValidationStatus.INVALID.ordinal, db)
        assertEquals(ValidationStatus.INVALID, conv.convertToEntityAttribute(db))
        assertThrows<IllegalStateException> { conv.convertToEntityAttribute(999) }
    }

    // validation-status-truthful-pending.md R2/R7 — the existing 0..3 mapping
    // (PENDING/VALID/INVALID/UNAVAILABLE) must never shift; NOT_VALIDATED is appended last,
    // at ordinal/db value 4.
    @Test
    fun `every ValidationStatus value round-trips through the converter`() {
        ValidationStatus.entries.forEach { status ->
            val db = conv.convertToDatabaseColumn(status)
            assertEquals(status, conv.convertToEntityAttribute(db))
        }
    }

    @Test
    fun `db value 4 maps to NOT_VALIDATED and the pre-existing 0-3 mapping is unchanged`() {
        assertEquals(0, conv.convertToDatabaseColumn(ValidationStatus.PENDING))
        assertEquals(1, conv.convertToDatabaseColumn(ValidationStatus.VALID))
        assertEquals(2, conv.convertToDatabaseColumn(ValidationStatus.INVALID))
        assertEquals(3, conv.convertToDatabaseColumn(ValidationStatus.UNAVAILABLE))
        assertEquals(4, conv.convertToDatabaseColumn(ValidationStatus.NOT_VALIDATED))
        assertEquals(ValidationStatus.NOT_VALIDATED, conv.convertToEntityAttribute(4))
    }
}
