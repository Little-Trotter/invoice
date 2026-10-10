package de.littletrotter.domain.value

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PO report 04.09.2026 — "die VIES-Abfrage beim Kunden funktioniert nicht".
 *
 * [VatIdValidator.validate] normalised its input internally and then reported "DE 136 695 976"
 * valid, while `RecipientView.verifyVatId` split the RAW field value and handed the number —
 * spaces included — to [de.littletrotter.infrastructure.vies.ViesHttpClient], which builds it into
 * a URL path segment. `URI.create` rejects a space, the surrounding catch turned that into
 * UNREACHABLE, and the user was told the service was down. These cases pin the shared
 * normalisation that fix rests on, so the two sides can no longer disagree about what a VAT id is.
 */
class VatIdNormalizationTest {

    // ── normalize: what actually goes on the wire ────────────────────────────────

    @Test
    fun `inner spaces are removed, not just the outer ones`() {
        assertEquals("DE136695976", VatIdValidator.normalize("  DE 136 695 976  "))
    }

    @Test
    fun `the value is upper-cased`() {
        assertEquals("DE136695976", VatIdValidator.normalize("de136695976"))
        assertEquals("NL123456789B01", VatIdValidator.normalize("nl123456789b01"))
    }

    @Test
    fun `tabs and line breaks count as whitespace too`() {
        assertEquals("DE136695976", VatIdValidator.normalize("DE\t136\n695\r976"))
    }

    /** A VAT id copied out of a web page or a PDF regularly carries U+00A0, which Java's `\s`
     *  does not match — the reason [VatIdValidator.normalize] also spans `\p{Z}`. */
    @Test
    fun `the non-breaking space is removed as well`() {
        assertEquals("DE136695976", VatIdValidator.normalize("DE 136 695 976"))
        assertEquals("DE136695976", VatIdValidator.normalize("DE 136 695976"))
    }

    @Test
    fun `null and blank normalise to the empty string rather than throwing`() {
        assertEquals("", VatIdValidator.normalize(null))
        assertEquals("", VatIdValidator.normalize("   "))
    }

    /** Whitespace only — never the separators a format legitimately carries. The US pattern
     *  allows a hyphen, so stripping those would break a valid input. */
    @Test
    fun `separators that belong to a format survive`() {
        assertEquals("US12-3456789", VatIdValidator.normalize("us 12-3456789"))
    }

    // ── prefix and number: the country code comes from the NUMBER ─────────────────

    @Test
    fun `prefix and number are split off the normalised value`() {
        assertEquals("DE", VatIdValidator.prefixOf(" de 136 695 976 "))
        assertEquals("136695976", VatIdValidator.numberOf(" de 136 695 976 "))
    }

    /**
     * Greece is the case that proves the country code is taken from the number and not from an ISO
     * table: its VAT prefix is EL while its ISO-3166 code is GR, and VIES speaks the VAT prefix.
     */
    @Test
    fun `Greece keeps its VAT prefix EL, not its ISO code GR`() {
        assertEquals("EL", VatIdValidator.prefixOf("EL 123 456 789"))
        assertEquals("123456789", VatIdValidator.numberOf("EL 123 456 789"))
        assertTrue(VatIdValidator.isViesCountry("EL123456789"))
    }

    @Test
    fun `a value too short to carry a prefix yields empty parts instead of a substring crash`() {
        assertEquals("", VatIdValidator.prefixOf("D"))
        assertEquals("", VatIdValidator.numberOf("DE"))
        assertEquals("", VatIdValidator.numberOf(null))
    }

    // ── plausibility: VIES only answers for EU member states ─────────────────────

    @Test
    fun `every EU prefix the validator knows is a VIES country`() {
        listOf("AT", "BE", "DE", "FR", "IT", "LU", "NL", "PL", "SE").forEach {
            assertTrue(VatIdValidator.isViesCountry("${it}123456789"), it)
        }
    }

    /**
     * These have a recognised format but no VIES entry. Asking anyway used to return UNREACHABLE,
     * which reads as "the service is down" — the caller refuses up front instead.
     */
    @Test
    fun `formats outside the EU are recognised but are not VIES countries`() {
        listOf("GB123456789", "NO123456789MVA", "US12-3456789", "CA123456789", "AU12345678901", "NZ12345678")
            .forEach {
                assertTrue(VatIdValidator.isValid(it), "$it should still be a valid format")
                assertFalse(VatIdValidator.isViesCountry(it), "$it must not be sent to VIES")
            }
    }

    @Test
    fun `an unknown or missing prefix is not a VIES country`() {
        assertFalse(VatIdValidator.isViesCountry("XX123456789"))
        assertFalse(VatIdValidator.isViesCountry(""))
        assertFalse(VatIdValidator.isViesCountry(null))
    }

    // ── validate keeps agreeing with normalize ───────────────────────────────────

    @Test
    fun `a spaced VAT id is valid and normalises to exactly what the format check accepted`() {
        val spaced = "DE 136 695 976"
        assertTrue(VatIdValidator.isValid(spaced))
        assertTrue(VatIdValidator.isValid(VatIdValidator.normalize(spaced)))
        assertEquals(VatIdValidator.normalize(spaced), VatIdValidator.normalize(VatIdValidator.normalize(spaced)))
    }
}
