package de.littletrotter.domain.masterdata

import de.littletrotter.shared.country.Country
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CountryAttributeConverterTest {
    private val conv = CountryAttributeConverter()

    @Test
    fun `null handling`() {
        assertNull(conv.convertToDatabaseColumn(null))
        assertNull(conv.convertToEntityAttribute(null))
    }

    @Test
    fun `valid numeric maps and invalid fallback`() {
        val db = conv.convertToDatabaseColumn(Country.DE)
        assertEquals(Country.DE.numeric, db)
        assertEquals(Country.DE, conv.convertToEntityAttribute(db))
        // invalid numeric should fallback to DE per converter implementation
        assertEquals(Country.DE, conv.convertToEntityAttribute(9999))
    }
}
