package de.littletrotter.domain.value

import de.littletrotter.shared.country.Country
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

class VatIdValidatorTest {

    // ─── Blank / null ──────────────────────────────────────────────────────────

    @Test
    fun `null is valid`() {
        assertTrue(VatIdValidator.isValid(null))
    }

    @Test
    fun `blank is valid`() {
        assertTrue(VatIdValidator.isValid("   "))
    }

    @Test
    fun `empty string is valid`() {
        assertTrue(VatIdValidator.isValid(""))
    }

    // ─── German (DE) ──────────────────────────────────────────────────────────

    @Test
    fun `valid German VAT ID`() {
        assertTrue(VatIdValidator.isValid("DE123456789"))
    }

    @Test
    fun `German VAT ID with spaces is normalised and valid`() {
        assertTrue(VatIdValidator.isValid("DE 123 456 789"))
    }

    @Test
    fun `German VAT ID too short is invalid`() {
        assertFalse(VatIdValidator.isValid("DE12345678")) // 8 digits, needs 9
    }

    @Test
    fun `German VAT ID too long is invalid`() {
        assertFalse(VatIdValidator.isValid("DE1234567890")) // 10 digits
    }

    // ─── Austrian (AT) ────────────────────────────────────────────────────────

    @Test
    fun `valid Austrian VAT ID`() {
        assertTrue(VatIdValidator.isValid("ATU12345678"))
    }

    @Test
    fun `Austrian VAT ID without U is invalid`() {
        assertFalse(VatIdValidator.isValid("AT12345678"))
    }

    // ─── French (FR) ──────────────────────────────────────────────────────────

    @Test
    fun `valid French VAT ID with letters`() {
        assertTrue(VatIdValidator.isValid("FRXX123456789"))
    }

    @Test
    fun `valid French VAT ID with digits`() {
        assertTrue(VatIdValidator.isValid("FR12123456789"))
    }

    // ─── British (GB) ─────────────────────────────────────────────────────────

    @Test
    fun `valid British VAT ID 9 digits`() {
        assertTrue(VatIdValidator.isValid("GB123456789"))
    }

    @Test
    fun `valid British VAT ID 12 digits`() {
        assertTrue(VatIdValidator.isValid("GB123456789012"))
    }

    @Test
    fun `valid British VAT ID GD prefix`() {
        assertTrue(VatIdValidator.isValid("GBGD001"))
    }

    @Test
    fun `valid British VAT ID HA prefix`() {
        assertTrue(VatIdValidator.isValid("GBHA999"))
    }

    // ─── Norwegian (NO) ───────────────────────────────────────────────────────

    @Test
    fun `valid Norwegian VAT ID`() {
        assertTrue(VatIdValidator.isValid("NO123456789MVA"))
    }

    @Test
    fun `Norwegian VAT ID without MVA is invalid`() {
        assertFalse(VatIdValidator.isValid("NO123456789"))
    }

    // ─── Various other EU countries ────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(
        strings = [
            "BE0123456789",   // Belgium
            "BG123456789",    // Bulgaria (9 digits)
            "BG1234567890",   // Bulgaria (10 digits)
            "CY12345678A",    // Cyprus
            "CZ12345678",     // Czech Republic (8 digits)
            "DK12345678",     // Denmark
            "EE123456789",    // Estonia
            "EL123456789",    // Greece
            "ESA1234567A",    // Spain
            "FI12345678",     // Finland
            "HR12345678901",  // Croatia
            "HU12345678",     // Hungary
            "IT12345678901",  // Italy
            "LT123456789",    // Lithuania (9 digits)
            "LU12345678",     // Luxembourg
            "LV12345678901",  // Latvia
            "MT12345678",     // Malta
            "NL123456789B01", // Netherlands
            "PL1234567890",   // Poland
            "PT123456789",    // Portugal
            "RO12345678",     // Romania
            "SE123456789012", // Sweden
            "SI12345678",     // Slovenia
            "SK1234567890"    // Slovakia
        ],
    )
    fun `valid EU VAT IDs`(vatId: String) {
        val error = VatIdValidator.validate(vatId)
        assertTrue(error == null, { "Expected valid but got error for $vatId: $error" })
    }

    // ─── Unknown prefix ───────────────────────────────────────────────────────

    @Test
    fun `unknown country prefix is invalid`() {
        val error = VatIdValidator.validate("XX123456789")
        assertTrue(error != null)
        assertTrue(error!!.contains("XX"))
    }

    @Test
    fun `boundary - a value shorter than a two-character prefix reports the NO_PREFIX message`() {
        val error = VatIdValidator.validate("D")
        assertTrue(error != null)
        assertTrue(error!!.contains("2-stelligen"), "must name the missing prefix, not a wrong-format/unknown-prefix message: $error")
    }

    // ─── Error message content ─────────────────────────────────────────────────

    @Test
    fun `invalid format provides helpful message`() {
        val error = VatIdValidator.validate("DE12345") // too short
        assertTrue(error != null)
        assertTrue(error!!.contains("DE"))
    }

    // ─── Wave E (2026-08-30 evening) — 64-character column width ────────────────
    // sender_profile.vat_id / recipient.vat_id were raised from VARCHAR(30) to VARCHAR(64); this
    // pair proves the new boundary is enforced by the validator's own length guard, not
    // incidentally by whichever "unknown prefix"/"wrong format" message a random long string
    // happens to trigger.

    @Test
    fun `a value at exactly the 64-character limit is not rejected for its length`() {
        val exactly64 = "XX" + "1".repeat(62)
        assertEquals(64, exactly64.length)
        val error = VatIdValidator.validate(exactly64)
        // "XX" is not a recognised prefix, so the value is still invalid overall -- but not
        // because of its length, which is the one thing this test is about.
        assertTrue(error != null)
        assertTrue(!error!!.contains("Zeichen"), "a 64-character value must not be rejected for its length: $error")
    }

    @Test
    fun `a value one character over the 64-character limit is rejected specifically for its length`() {
        val over64 = "XX" + "1".repeat(63)
        assertEquals(65, over64.length)
        val error = VatIdValidator.validate(over64)
        assertTrue(error != null)
        assertTrue(error!!.contains("64"), "the message must name the limit: $error")
    }

    // ─── Supported country set ────────────────────────────────────────────────

    @Test
    fun `supported country prefixes contains all EU member states`() {
        val prefixes = VatIdValidator.supportedCountryPrefixes()
        // spot-check a selection of EU member states
        assertTrue(prefixes.contains("DE"))
        assertTrue(prefixes.contains("FR"))
        assertTrue(prefixes.contains("IT"))
        assertTrue(prefixes.contains("ES"))
        assertTrue(prefixes.contains("PL"))
        // and non-EU supported countries
        assertTrue(prefixes.contains("GB"))
        assertTrue(prefixes.contains("NO"))
        // newly supported non-EU countries
        assertTrue(prefixes.contains("US"))
        assertTrue(prefixes.contains("CA"))
        assertTrue(prefixes.contains("AU"))
        assertTrue(prefixes.contains("NZ"))
        assertTrue(prefixes.contains("CH"))
    }

    // ─── vatPrefixFor ─────────────────────────────────────────────────────────

    @Test
    fun `vatPrefixFor returns the alpha2 code for a country whose prefix matches it`() {
        assertEquals("DE", VatIdValidator.vatPrefixFor(Country.DE))
        assertEquals("AT", VatIdValidator.vatPrefixFor(Country.AT))
        assertEquals("GB", VatIdValidator.vatPrefixFor(Country.GB))
    }

    @Test
    fun `vatPrefixFor returns EL for Greece, not its ISO code GR`() {
        assertEquals("EL", VatIdValidator.vatPrefixFor(Country.GR))
    }

    @Test
    fun `vatPrefixFor returns CH for Switzerland`() {
        assertEquals("CH", VatIdValidator.vatPrefixFor(Country.CH))
    }

    @Test
    fun `vatPrefixFor returns null for a country with no recognised VAT-id format`() {
        assertNull(VatIdValidator.vatPrefixFor(Country.IN))
    }

    // ─── exampleFor ───────────────────────────────────────────────────────────

    @Test
    fun `exampleFor provides a well-formed example for every recognised prefix, each passing validate`() {
        VatIdValidator.supportedCountryPrefixes().forEach { prefix ->
            val example = VatIdValidator.exampleFor(prefix)
            assertTrue(example.startsWith(prefix), "example '$example' for prefix '$prefix' must itself start with that prefix")
            val error = VatIdValidator.validate(example)
            assertTrue(error == null, "example '$example' for prefix '$prefix' must itself pass validate(): $error")
        }
    }

    @Test
    fun `exampleFor falls back to the German example for an unrecognised prefix`() {
        assertEquals(VatIdValidator.exampleFor("DE"), VatIdValidator.exampleFor("XX"))
        assertEquals(VatIdValidator.exampleFor("DE"), VatIdValidator.exampleFor(""))
    }

    // ─── violationOf ──────────────────────────────────────────────────────────

    @Test
    fun `violationOf is null for blank or valid input`() {
        assertNull(VatIdValidator.violationOf(null))
        assertNull(VatIdValidator.violationOf("   "))
        assertNull(VatIdValidator.violationOf("DE123456789"))
    }

    @Test
    fun `violationOf is TOO_LONG for a value over the 64-character limit`() {
        val over64 = "XX" + "1".repeat(63)
        assertEquals(VatIdValidator.Violation.TOO_LONG, VatIdValidator.violationOf(over64))
    }

    @Test
    fun `violationOf is NO_PREFIX for a value shorter than a two-character prefix`() {
        assertEquals(VatIdValidator.Violation.NO_PREFIX, VatIdValidator.violationOf("D"))
    }

    @Test
    fun `violationOf is UNKNOWN_PREFIX for a syntactically complete but unrecognised prefix`() {
        assertEquals(VatIdValidator.Violation.UNKNOWN_PREFIX, VatIdValidator.violationOf("XX123456789"))
    }

    @Test
    fun `violationOf is FORMAT for a recognised prefix whose number does not match the pattern`() {
        assertEquals(VatIdValidator.Violation.FORMAT, VatIdValidator.violationOf("DE12345"))
    }

    // ─── request path — all 27 VIES-covered prefixes ─────────────────────────

    @ParameterizedTest
    @MethodSource("viesPrefixes")
    fun `every VIES-covered prefix is recognised, formattable and normalises identically`(prefix: String) {
        assertTrue(VatIdValidator.isViesCountry(VatIdValidator.exampleFor(prefix)), prefix)
        assertEquals(prefix, VatIdValidator.prefixOf(VatIdValidator.exampleFor(prefix)))
    }

    // ─── prefixOf edge cases ──────────────────────────────────────────────────

    @Test
    fun `prefixOf extracts the two-character country prefix`() {
        assertEquals("DE", VatIdValidator.prefixOf("DE123456789"))
        assertEquals("AT", VatIdValidator.prefixOf("ATU12345678"))
        assertEquals("GB", VatIdValidator.prefixOf("GB123456789"))
    }

    @Test
    fun `prefixOf returns empty when VAT ID is shorter than 2 characters`() {
        assertEquals("", VatIdValidator.prefixOf("D"))
        assertEquals("", VatIdValidator.prefixOf(""))
        assertEquals("", VatIdValidator.prefixOf(null))
    }

    @Test
    fun `prefixOf normalises input before extracting prefix`() {
        assertEquals("DE", VatIdValidator.prefixOf("de 123 456 789"))
        assertEquals("DE", VatIdValidator.prefixOf("  DE  123  456  789  "))
    }

    // ─── numberOf edge cases ──────────────────────────────────────────────────

    @Test
    fun `numberOf extracts the numeric part after the prefix`() {
        assertEquals("123456789", VatIdValidator.numberOf("DE123456789"))
        // Austria's VAT id format itself starts with "U" (e.g. "ATU12345678") — numberOf() only
        // drops the 2-letter country prefix, so the "U" stays part of the returned number.
        assertEquals("U12345678", VatIdValidator.numberOf("ATU12345678"))
    }

    @Test
    fun `numberOf returns empty when VAT ID has no numeric part`() {
        assertEquals("", VatIdValidator.numberOf("D"))
        assertEquals("", VatIdValidator.numberOf(""))
        assertEquals("", VatIdValidator.numberOf(null))
    }

    @Test
    fun `numberOf normalises input before extracting number`() {
        assertEquals("123456789", VatIdValidator.numberOf("de 123 456 789"))
    }

    // ─── normalize edge cases ──────────────────────────────────────────────────

    @Test
    fun `normalize handles various whitespace characters`() {
        assertEquals("DE123456789", VatIdValidator.normalize("DE 123 456 789"))
        assertEquals("DE123456789", VatIdValidator.normalize("DE\t123\t456\t789"))
    }

    @Test
    fun `normalize uppercases the result`() {
        assertEquals("DE123456789", VatIdValidator.normalize("de123456789"))
        assertEquals("DE123456789", VatIdValidator.normalize("De123456789"))
    }

    companion object {
        @JvmStatic
        fun viesPrefixes(): List<String> = listOf(
            "AT", "BE", "BG", "CY", "CZ", "DE", "DK", "EE", "EL", "ES", "FI", "FR", "HR", "HU",
            "IE", "IT", "LT", "LU", "LV", "MT", "NL", "PL", "PT", "RO", "SE", "SI", "SK",
        )
    }
}
