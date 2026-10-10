package de.littletrotter.domain.value

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test

class EmailValidatorTest {

    @Test
    fun `null is valid`() {
        assertTrue(EmailValidator.isValid(null))
    }

    @Test
    fun `blank is valid`() {
        assertTrue(EmailValidator.isValid("   "))
    }

    @Test
    fun `empty string is valid`() {
        assertTrue(EmailValidator.isValid(""))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "name@beispiel.de",
            "vorname.nachname@firma.com",
            "info+tag@example.org",
            "user@sub.domain.de",
            "user123@domain123.net",
            "a@b.co",
        ],
    )
    fun `valid email addresses`(email: String) {
        assertTrue(EmailValidator.isValid(email), "Expected valid: $email")
    }

    @Test
    fun `leading and trailing spaces are trimmed`() {
        assertTrue(EmailValidator.isValid("  name@beispiel.de  "))
    }

    @Test
    fun `missing at sign is invalid`() {
        assertFalse(EmailValidator.isValid("namebeisp.de"))
    }

    @Test
    fun `missing domain is invalid`() {
        assertFalse(EmailValidator.isValid("name@"))
    }

    @Test
    fun `missing tld is invalid`() {
        assertFalse(EmailValidator.isValid("name@domain"))
    }

    @Test
    fun `missing local part is invalid`() {
        assertFalse(EmailValidator.isValid("@domain.de"))
    }

    @Test
    fun `space in address is invalid`() {
        assertFalse(EmailValidator.isValid("na me@domain.de"))
    }

    @Test
    fun `double at sign is invalid`() {
        assertFalse(EmailValidator.isValid("name@@domain.de"))
    }

    @Test
    fun `error message contains hint`() {
        val error = EmailValidator.validate("notanemail")
        assertNotNull(error)
        assertTrue(error!!.contains("E-Mail"))
    }
}
