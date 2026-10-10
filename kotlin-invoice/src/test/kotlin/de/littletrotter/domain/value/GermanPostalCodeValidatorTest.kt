package de.littletrotter.domain.value

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class GermanPostalCodeValidatorTest {

    @Test
    fun `null is valid`() {
        assertTrue(GermanPostalCodeValidator.isValid(null))
    }

    @Test
    fun `blank is valid`() {
        assertTrue(GermanPostalCodeValidator.isValid("   "))
    }

    @Test
    fun `empty string is valid`() {
        assertTrue(GermanPostalCodeValidator.isValid(""))
    }

    @ParameterizedTest
    @ValueSource(strings = ["10115", "80331", "01067", "99998", "12345", "00001"])
    fun `valid five digit postal code`(postalCode: String) {
        assertTrue(GermanPostalCodeValidator.isValid(postalCode))
    }

    @Test
    fun `leading and trailing spaces are trimmed`() {
        assertTrue(GermanPostalCodeValidator.isValid("  10115  "))
    }

    @Test
    fun `four digits is invalid`() {
        assertFalse(GermanPostalCodeValidator.isValid("1234"))
    }

    @Test
    fun `six digits is invalid`() {
        assertFalse(GermanPostalCodeValidator.isValid("123456"))
    }

    @Test
    fun `letters are invalid`() {
        assertFalse(GermanPostalCodeValidator.isValid("1234A"))
    }

    @Test
    fun `special characters are invalid`() {
        assertFalse(GermanPostalCodeValidator.isValid("12-34"))
    }

    @Test
    fun `error message contains expected hint`() {
        val error = GermanPostalCodeValidator.validate("abc")
        assertTrue(error.isPresent)
        assertTrue(error.get().contains("5 Ziffern"))
    }
}
