package de.littletrotter.domain.value

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class IbanValidatorTest {

    // ─── Blank / null ──────────────────────────────────────────────────────────

    @Test
    fun `null is valid`() {
        assertTrue(IbanValidator.isValid(null))
    }

    @Test
    fun `blank is valid`() {
        assertTrue(IbanValidator.isValid("   "))
    }

    @Test
    fun `empty string is valid`() {
        assertTrue(IbanValidator.isValid(""))
    }

    // ─── Valid IBANs ───────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(
        strings = [
            "DE89370400440532013000",        // German IBAN (no spaces)
            "DE89 3704 0044 0532 0130 00",   // German IBAN (with spaces)
            "GB29NWBK60161331926819",        // British IBAN
            "AT611904300234573201",          // Austrian IBAN
            "FR7630006000011234567890189",   // French IBAN
            "NL91ABNA0417164300",            // Dutch IBAN
        ],
    )
    fun `valid IBANs`(iban: String) {
        val error = IbanValidator.validate(iban)
        assertTrue(error == null, { "Expected valid but got: $error" })
    }

    // ─── Invalid IBANs ─────────────────────────────────────────────────────────

    @Test
    fun `wrong checksum is invalid`() {
        // DE89 is correct; DE88 has wrong checksum
        assertFalse(IbanValidator.isValid("DE88370400440532013000"))
    }

    @Test
    fun `too short is invalid`() {
        assertFalse(IbanValidator.isValid("DE89"))
    }

    @Test
    fun `all digits is invalid`() {
        assertFalse(IbanValidator.isValid("12345678901234567890"))
    }

    @Test
    fun `country code must be letters`() {
        assertFalse(IbanValidator.isValid("12370400440532013000"))
    }

    @Test
    fun `invalid characters are rejected`() {
        assertFalse(IbanValidator.isValid("DE89!704004405320130"))
    }

    // ─── Error message ─────────────────────────────────────────────────────────

    @Test
    fun `error message for bad format contains hint`() {
        val error = IbanValidator.validate("NOTANIBAN")
        assertTrue(error != null)
        assertTrue(error!!.contains("IBAN"))
    }

    @Test
    fun `error message for bad checksum contains hint`() {
        val error = IbanValidator.validate("DE00370400440532013000")
        assertTrue(error != null)
        assertTrue(error!!.contains("Prüfsumme"))
    }
}
