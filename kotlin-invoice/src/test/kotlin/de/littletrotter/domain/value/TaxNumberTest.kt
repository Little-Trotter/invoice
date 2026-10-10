package de.littletrotter.domain.value

import de.littletrotter.domain.exception.InvalidParameterException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** . */
class TaxNumberTest {

    @Test
    fun `happy path accepts a slash-separated tax number`() {
        val taxNumber = TaxNumber("181/815/08155")
        assertEquals("181/815/08155", taxNumber.value)
        assertEquals("181/815/08155", taxNumber.toString())
    }

    @Test
    fun `happy path accepts an unseparated 10-digit tax number`() {
        assertEquals("1118150815", TaxNumber("1118150815").value)
    }

    @Test
    fun `too short is rejected`() {
        assertThrows(InvalidParameterException::class.java) { TaxNumber("12345678") }
    }

    @Test
    fun `too long is rejected`() {
        assertThrows(InvalidParameterException::class.java) { TaxNumber("12345678901234") }
    }

    @Test
    fun `letters are rejected`() {
        assertThrows(InvalidParameterException::class.java) { TaxNumber("1234ABC5678") }
    }

    @Test
    fun `a blank tax number is rejected`() {
        assertThrows(InvalidParameterException::class.java) { TaxNumber("   ") }
    }

    @Test
    fun `boundary exactly MAX_LENGTH characters is accepted`() {
        // sender_profile.tax_number/invoice_party_snapshot.tax_number are VARCHAR(50); padding
        // spaces are stripped by GermanTaxNumberValidator's own normalisation, so this is both a
        // valid tax number AND exactly 50 raw characters.
        val padded = " ".repeat(TaxNumber.MAX_LENGTH - "181/815/08155".length) + "181/815/08155"
        assertEquals(TaxNumber.MAX_LENGTH, padded.length)
        assertEquals(padded, TaxNumber(padded).value)
    }

    @Test
    fun `boundary MAX_LENGTH plus one character is rejected`() {
        val tooLong = " ".repeat(TaxNumber.MAX_LENGTH + 1 - "181/815/08155".length) + "181/815/08155"
        assertEquals(TaxNumber.MAX_LENGTH + 1, tooLong.length)
        assertThrows(InvalidParameterException::class.java) { TaxNumber(tooLong) }
    }

    @Test
    fun `of trims but keeps slash separators — no normalisation beyond whitespace`() {
        assertEquals("181/815/08155", TaxNumber.of(" 181/815/08155 ").value)
    }

    @Test
    fun `ofNullable returns null for blank or null input`() {
        assertNull(TaxNumber.ofNullable(null))
        assertNull(TaxNumber.ofNullable("   "))
    }

    @Test
    fun `converter round-trips a value through the database column type`() {
        val converter = TaxNumberConverter()
        val taxNumber = TaxNumber("1118150815")
        assertEquals("1118150815", converter.convertToDatabaseColumn(taxNumber))
        assertEquals(taxNumber, converter.convertToEntityAttribute("1118150815"))
    }

    @Test
    fun `converter is null-safe in both directions`() {
        val converter = TaxNumberConverter()
        assertNull(converter.convertToDatabaseColumn(null))
        assertNull(converter.convertToEntityAttribute(null))
    }
}
