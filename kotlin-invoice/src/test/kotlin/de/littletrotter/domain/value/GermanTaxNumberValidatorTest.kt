package de.littletrotter.domain.value

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class GermanTaxNumberValidatorTest {

    @Test
    fun `null is valid`() {
        assertTrue(GermanTaxNumberValidator.isValid(null))
    }

    @Test
    fun `blank is valid`() {
        assertTrue(GermanTaxNumberValidator.isValid("   "))
    }

    @Test
    fun `empty string is valid`() {
        assertTrue(GermanTaxNumberValidator.isValid(""))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "181/815/08155",
            "28/815/08155",
            "1118150815",
            "11181508155",
            "2893081508152",
        ],
    )
    fun `valid tax numbers`(taxNumber: String) {
        assertTrue(GermanTaxNumberValidator.isValid(taxNumber), "Expected valid: $taxNumber")
    }

    @Test
    fun `tax number with spaces is normalised and valid`() {
        assertTrue(GermanTaxNumberValidator.isValid("181 815 08155"))
    }

    @Test
    fun `too short is invalid`() {
        assertFalse(GermanTaxNumberValidator.isValid("12345678"))
    }

    @Test
    fun `too long is invalid`() {
        assertFalse(GermanTaxNumberValidator.isValid("12345678901234"))
    }

    @Test
    fun `letters are invalid`() {
        assertFalse(GermanTaxNumberValidator.isValid("1234ABC5678"))
    }

    @Test
    fun `error message contains hint`() {
        val error = GermanTaxNumberValidator.validate("1234")
        assertTrue(error.isPresent)
        assertTrue(error.get().contains("10"))
    }
}
