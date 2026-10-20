package de.littletrotter.domain.value

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/** L20 — the country gate around [GermanPostalCodeValidator] plus the lenient foreign rule. */
class PostalCodeValidatorTest {

    // ── isGermanCountry ──

    @ParameterizedTest
    @ValueSource(strings = ["DE", "de", " DE "])
    fun `DE in any casing or padding counts as German`(country: String) {
        assertTrue(PostalCodeValidator.isGermanCountry(country))
    }

    @Test
    fun `a missing country counts as German - the address forms default to DE`() {
        assertTrue(PostalCodeValidator.isGermanCountry(null))
        assertTrue(PostalCodeValidator.isGermanCountry(""))
        assertTrue(PostalCodeValidator.isGermanCountry("   "))
    }

    @ParameterizedTest
    @ValueSource(strings = ["LU", "NL", "FR", "AT", "CH"])
    fun `foreign alpha-2 codes are not German`(country: String) {
        assertFalse(PostalCodeValidator.isGermanCountry(country))
    }

    // ── German addresses: the strict five-digit rule applies ──

    @ParameterizedTest
    @ValueSource(strings = ["10115", "80331", "01067"])
    fun `a German address accepts a five-digit PLZ`(postalCode: String) {
        assertNull(PostalCodeValidator.check("DE", postalCode))
    }

    @ParameterizedTest
    @ValueSource(strings = ["1234", "123456", "L-1611", "1234 AB", "AB123"])
    fun `a German address rejects every non-five-digit shape`(postalCode: String) {
        assertEquals(PostalCodeValidator.Violation.GERMAN_FORMAT, PostalCodeValidator.check("DE", postalCode))
    }

    @Test
    fun `a missing country enforces the German rule`() {
        assertEquals(PostalCodeValidator.Violation.GERMAN_FORMAT, PostalCodeValidator.check(null, "L-1611"))
        assertNull(PostalCodeValidator.check(null, "10115"))
    }

    // ── Foreign addresses: real European formats pass, no per-country format table ──

    @ParameterizedTest
    @CsvSource(
        "LU, L-1611",
        "LU, 1611",
        "NL, 1234 AB",
        "FR, 75008",
        "AT, 1010",
        "CH, 8001",
        "BE, 1000",
    )
    fun `real foreign postal codes are accepted for their country`(country: String, postalCode: String) {
        assertNull(PostalCodeValidator.check(country, postalCode))
        assertTrue(PostalCodeValidator.isValid(country, postalCode))
    }

    @Test
    fun `a foreign postal code longer than the sanity cap is rejected`() {
        assertEquals(PostalCodeValidator.Violation.TOO_LONG, PostalCodeValidator.check("LU", "L-16110000000"))
        assertEquals(PostalCodeValidator.Violation.TOO_LONG, PostalCodeValidator.check("NL", "12345678901"))
    }

    @Test
    fun `the sanity cap trims surrounding whitespace before measuring`() {
        assertNull(PostalCodeValidator.check("LU", "  L-1611  "))
    }

    @Test
    fun `exactly the maximum length is still accepted`() {
        assertNull(PostalCodeValidator.check("LU", "A".repeat(PostalCodeValidator.MAX_LENGTH)))
    }

    // ── Blank stays valid everywhere: requiredness is the owning form's concern ──

    @Test
    fun `blank and null are valid regardless of country`() {
        assertNull(PostalCodeValidator.check("DE", null))
        assertNull(PostalCodeValidator.check("DE", "   "))
        assertNull(PostalCodeValidator.check("LU", null))
        assertNull(PostalCodeValidator.check("LU", ""))
    }

    @Test
    fun `violations carry a German message for the server-side backstop`() {
        assertTrue(PostalCodeValidator.Violation.GERMAN_FORMAT.message.contains("5 Ziffern"))
        assertTrue(PostalCodeValidator.Violation.TOO_LONG.message.contains("${PostalCodeValidator.MAX_LENGTH}"))
    }
}
