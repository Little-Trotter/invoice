package de.littletrotter.domain.value

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** ISO 9362 format validation. */
class BicValidatorTest {

    @Test
    fun `null is valid`() {
        assertTrue(BicValidator.isValid(null))
    }

    @Test
    fun `blank is valid`() {
        assertTrue(BicValidator.isValid("   "))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "DEUTDEFF", // 8-char, no branch code
            "DEUTDEFF500", // 11-char, with branch code
            "COBADEFFXXX",
            "PBNKDEFFXXX",
            "BELADEBEXXX",
            "BYLADEM1001", // digit in the location code (position 7-8)
        ],
    )
    fun `valid BICs`(bic: String) {
        assertTrue(BicValidator.isValid(bic), "Expected valid: $bic")
    }

    @Test
    fun `lower case and surrounding whitespace are tolerated`() {
        assertTrue(BicValidator.isValid("  deutdeff  "))
    }

    @Test
    fun `9 characters is invalid — only 8 or 11 are valid lengths`() {
        assertFalse(BicValidator.isValid("DEUTDEFFX"))
    }

    @Test
    fun `10 characters is invalid`() {
        assertFalse(BicValidator.isValid("DEUTDEFFXX"))
    }

    @Test
    fun `12 characters is invalid`() {
        assertFalse(BicValidator.isValid("DEUTDEFF5001"))
    }

    @Test
    fun `digits in the institution code (positions 1-4) are invalid`() {
        assertFalse(BicValidator.isValid("DEU1DEFF"))
    }

    @Test
    fun `digits in the country code (positions 5-6) are invalid`() {
        assertFalse(BicValidator.isValid("DEUT1EFF"))
    }

    @Test
    fun `error message names the expected shape`() {
        val error = BicValidator.validate("INVALID")
        assertTrue(error != null && error.contains("8"))
    }
}
