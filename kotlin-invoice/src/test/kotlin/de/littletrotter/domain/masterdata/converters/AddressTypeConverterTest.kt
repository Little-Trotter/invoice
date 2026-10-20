package de.littletrotter.domain.masterdata.converters

import de.littletrotter.domain.masterdata.Address
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AddressTypeConverterTest {
    private val conv = AddressTypeConverter()

    @Test
    fun `null handling`() {
        assertNull(conv.convertToDatabaseColumn(null))
        assertNull(conv.convertToEntityAttribute(null))
    }

    @Test
    fun `roundtrip and out of range`() {
        val db = conv.convertToDatabaseColumn(Address.AddressType.DELIVERY)
        assertEquals(Address.AddressType.DELIVERY.ordinal, db)
        assertEquals(Address.AddressType.DELIVERY, conv.convertToEntityAttribute(db))
        assertThrows<IllegalStateException> { conv.convertToEntityAttribute(999) }
    }
}
