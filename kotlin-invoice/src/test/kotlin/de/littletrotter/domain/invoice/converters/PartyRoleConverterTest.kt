package de.littletrotter.domain.invoice.converters

import de.littletrotter.domain.invoice.PartyRole
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PartyRoleConverterTest {
    private val conv = PartyRoleConverter()

    @Test
    fun `null handling`() {
        assertNull(conv.convertToDatabaseColumn(null))
        assertNull(conv.convertToEntityAttribute(null))
    }

    @Test
    fun `roundtrip all constants`() {
        PartyRole.entries.forEach { constant ->
            val dbValue = conv.convertToDatabaseColumn(constant)
            assertEquals(constant.ordinal, dbValue)
            assertEquals(constant, conv.convertToEntityAttribute(dbValue))
        }
    }

    @Test
    fun `boundary first and last ordinals`() {
        val first = PartyRole.entries.first()
        assertEquals(first.ordinal, conv.convertToDatabaseColumn(first))
        assertEquals(first, conv.convertToEntityAttribute(first.ordinal))

        val last = PartyRole.entries.last()
        assertEquals(last.ordinal, conv.convertToDatabaseColumn(last))
        assertEquals(last, conv.convertToEntityAttribute(last.ordinal))
    }

    @Test
    fun `unknown ordinal beyond last constant fails loudly`() {
        assertThrows<IllegalStateException> { conv.convertToEntityAttribute(PartyRole.entries.size) }
        assertThrows<IllegalStateException> { conv.convertToEntityAttribute(PartyRole.entries.size + 100) }
    }
}
