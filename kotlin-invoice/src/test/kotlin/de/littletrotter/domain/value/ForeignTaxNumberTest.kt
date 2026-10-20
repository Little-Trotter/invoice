package de.littletrotter.domain.value

import de.littletrotter.domain.exception.InvalidParameterException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ForeignTaxNumberTest {

    @Test
    fun `of stores the normalised form`() {
        assertEquals("1234567890123", ForeignTaxNumber.of(" 12 34.567-890/123 ").value)
        assertEquals("AB12", ForeignTaxNumber.of("ab-12").toString())
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "-./"])
    fun `blank after normalisation is rejected`(raw: String) {
        assertThrows(InvalidParameterException::class.java) { ForeignTaxNumber.of(raw) }
    }

    @Test
    fun `more than 50 characters is rejected, exactly 50 is accepted`() {
        ForeignTaxNumber.of("A".repeat(ForeignTaxNumber.MAX_LENGTH))
        assertThrows(InvalidParameterException::class.java) { ForeignTaxNumber.of("A".repeat(ForeignTaxNumber.MAX_LENGTH + 1)) }
    }

    @Test
    fun `characters other than letters and digits are rejected`() {
        assertThrows(InvalidParameterException::class.java) { ForeignTaxNumber.of("12#34") }
    }

    @Test
    fun `ofNullable maps blank to null`() {
        assertNull(ForeignTaxNumber.ofNullable(null))
        assertNull(ForeignTaxNumber.ofNullable("  "))
        assertEquals("123", ForeignTaxNumber.ofNullable("1 23")?.value)
    }

    @Test
    fun `companion exposes the length bound and toString returns the value`() {
        assertEquals(50, ForeignTaxNumber.maxLength)
        assertEquals(ForeignTaxNumber.MAX_LENGTH, ForeignTaxNumber.maxLength)
        assertEquals("AB12", ForeignTaxNumber("AB12").toString())
    }

    @Test
    fun `converter round-trips and keeps null`() {
        val converter = ForeignTaxNumberConverter()
        assertEquals("123", converter.convertToDatabaseColumn(ForeignTaxNumber("123")))
        assertNull(converter.convertToDatabaseColumn(null))
        assertEquals(ForeignTaxNumber("123"), converter.convertToEntityAttribute("123"))
        assertNull(converter.convertToEntityAttribute(null))
    }
}
