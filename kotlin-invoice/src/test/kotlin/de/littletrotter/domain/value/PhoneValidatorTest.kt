package de.littletrotter.domain.value

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhoneValidatorTest {

    // ── Valid numbers ─────────────────────────────────────────────────────────

    @Test
    fun `blank is valid`() {
        assertTrue(PhoneValidator.isValid(""))
        assertTrue(PhoneValidator.isValid("   "))
        assertTrue(PhoneValidator.isValid(null))
    }

    @Test
    fun `international format with plus is valid`() {
        assertTrue(PhoneValidator.isValid("+49 30 123456"))
        assertTrue(PhoneValidator.isValid("+1 800 555 1234"))
    }

    @Test
    fun `german local format is valid`() {
        assertTrue(PhoneValidator.isValid("030 123456"))
        assertTrue(PhoneValidator.isValid("030/123456"))
        assertTrue(PhoneValidator.isValid("0800-1234567"))
    }

    @Test
    fun `format with parentheses is valid`() {
        assertTrue(PhoneValidator.isValid("(089) 123456"))
    }

    // ── Invalid numbers ───────────────────────────────────────────────────────

    @Test
    fun `too few digits is invalid`() {
        assertFalse(PhoneValidator.isValid("123456"))   // only 6 digits
    }

    @Test
    fun `too many digits is invalid`() {
        assertFalse(PhoneValidator.isValid("1234567890123456"))  // 16 digits
    }

    @Test
    fun `letters are invalid`() {
        assertFalse(PhoneValidator.isValid("abc-123456"))
    }

    @Test
    fun `at-sign is invalid`() {
        assertFalse(PhoneValidator.isValid("test@example.de"))
    }

    // ── validate() returns message ────────────────────────────────────────────

    @Test
    fun `validate returns empty for valid number`() {
        assertNull(PhoneValidator.validate("+49 30 123456"))
    }

    @Test
    fun `validate returns message for invalid number`() {
        assertNotNull(PhoneValidator.validate("abc"))
    }
}
