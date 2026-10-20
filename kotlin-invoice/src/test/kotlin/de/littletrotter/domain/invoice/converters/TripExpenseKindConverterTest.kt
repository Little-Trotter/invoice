package de.littletrotter.domain.invoice.converters

import de.littletrotter.domain.invoice.TripExpenseKind
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TripExpenseKindConverterTest {
    private val conv = TripExpenseKindConverter()

    @Test
    fun `null handling`() {
        assertNull(conv.convertToDatabaseColumn(null))
        assertNull(conv.convertToEntityAttribute(null))
    }

    @Test
    fun `roundtrip all constants`() {
        TripExpenseKind.entries.forEach { constant ->
            val dbValue = conv.convertToDatabaseColumn(constant)
            assertEquals(constant.ordinal, dbValue)
            assertEquals(constant, conv.convertToEntityAttribute(dbValue))
        }
    }

    @Test
    fun `boundary first and last ordinals`() {
        val first = TripExpenseKind.entries.first()
        assertEquals(first.ordinal, conv.convertToDatabaseColumn(first))
        assertEquals(first, conv.convertToEntityAttribute(first.ordinal))

        val last = TripExpenseKind.entries.last()
        assertEquals(last.ordinal, conv.convertToDatabaseColumn(last))
        assertEquals(last, conv.convertToEntityAttribute(last.ordinal))
    }

    @Test
    fun `unknown ordinal beyond last constant returns null`() {
        assertNull(conv.convertToEntityAttribute(TripExpenseKind.entries.size))
        assertNull(conv.convertToEntityAttribute(TripExpenseKind.entries.size + 100))
    }
}
