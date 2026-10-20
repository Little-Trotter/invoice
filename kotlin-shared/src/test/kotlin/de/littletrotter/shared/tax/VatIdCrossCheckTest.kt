package de.littletrotter.shared.tax

import de.littletrotter.shared.country.Country
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VatIdCrossCheckTest {

    @Test
    fun `a matching pair is accepted`() {
        val result = VatIdCrossCheck.validate("DE111111111", "FR12345678901", Country.FR)
        assertTrue(result is VatIdCrossCheckResult.Ok)
    }

    @Test
    fun `a missing buyer vat id is rejected`() {
        val result = VatIdCrossCheck.validate("DE111111111", null, Country.FR)
        assertTrue(result is VatIdCrossCheckResult.Invalid)
    }

    @Test
    fun `a missing seller vat id is rejected`() {
        val result = VatIdCrossCheck.validate(null, "FR12345678901", Country.FR)
        assertTrue(result is VatIdCrossCheckResult.Invalid)
    }

    @Test
    fun `a blank, non-null buyer vat id is rejected the same as a missing one`() {
        val result = VatIdCrossCheck.validate("DE111111111", "   ", Country.FR)
        assertTrue(result is VatIdCrossCheckResult.Invalid)
    }

    @Test
    fun `a blank, non-null seller vat id is rejected the same as a missing one`() {
        val result = VatIdCrossCheck.validate("   ", "FR12345678901", Country.FR)
        assertTrue(result is VatIdCrossCheckResult.Invalid)
    }

    @Test
    fun `whitespace and dashes are normalized before comparison`() {
        val result = VatIdCrossCheck.validate("DE 111-111111", "FR 1234-5678901", Country.FR)
        assertTrue(result is VatIdCrossCheckResult.Ok)
    }

    @Test
    fun `normalize is case-insensitive`() {
        assertTrue(VatIdCrossCheck.normalize("fr12345678901") == VatIdCrossCheck.normalize("FR12345678901"))
    }
}
