package de.littletrotter.shared.country

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

private val FIXED_TODAY: LocalDate = LocalDate.of(2026, 6, 15)

class CountryTest {

    // ── EU membership with time bounds ──────────────────────────

    @Test
    fun `GB was an EU member before the Brexit transition period ended`() {
        assertTrue(Country.GB.isEuMemberOn(LocalDate.of(2020, 12, 31)))
        assertTrue(Country.GB.isEuMemberOn(LocalDate.of(1990, 1, 1)))
    }

    @Test
    fun `GB is not an EU member after the Brexit transition period`() {
        assertFalse(Country.GB.isEuMemberOn(LocalDate.of(2021, 1, 1)))
        assertFalse(Country.GB.isEuMemberOn(LocalDate.now()))
    }

    @Test
    fun `a country never in the EU is never a member`() {
        assertFalse(Country.CH.isEuMemberOn(LocalDate.now()))
        assertFalse(Country.US.isEuMemberOn(LocalDate.now()))
        assertFalse(Country.NO.isEuMemberOn(LocalDate.now()))
    }

    @Test
    fun `a member is not counted before its accession date`() {
        // HR joined 2013-07-01
        assertFalse(Country.HR.isEuMemberOn(LocalDate.of(2013, 6, 30)))
        assertTrue(Country.HR.isEuMemberOn(LocalDate.of(2013, 7, 1)))
    }

    @Test
    fun `all supported EU-B2B target countries are EU members today`() {
        Country.supportedEuB2bTargets(today = FIXED_TODAY).forEach { country ->
            assertTrue(country.isEuMemberOn(LocalDate.now()), "$country should be an EU member today")
        }
    }

    @Test
    fun `SUPPORTED_EU_B2B_TARGETS is every current EU member except Germany, same as the seller-registration set`() {
        // PO decision (2026-08-25) supersedes the old curated 8-country MVP list — this is now
        // the same "all 27 minus DE" set already used for SUPPORTED_SELLER_VAT_REGISTRATION_COUNTRIES.
        assertEquals(Country.supportedSellerVatRegistrationCountries(today = FIXED_TODAY), Country.supportedEuB2bTargets(today = FIXED_TODAY))
        assertTrue(Country.IT in Country.supportedEuB2bTargets(today = FIXED_TODAY), "Italy should now be an approved reverse-charge target")
        assertFalse(Country.DE in Country.supportedEuB2bTargets(today = FIXED_TODAY))
        assertFalse(Country.GB in Country.supportedEuB2bTargets(today = FIXED_TODAY))
    }

    @Test
    fun `fromAlpha2 is case insensitive and returns correct enum`() {
        assertEquals(Country.DE, Country.fromAlpha2("DE"))
        assertEquals(Country.DE, Country.fromAlpha2("de"))
        assertEquals(Country.US, Country.fromAlpha2("us"))
    }

    @Test
    fun `fromNumeric returns correct country or null`() {
        assertEquals(Country.DE, Country.fromNumeric(276))
        assertEquals(null, Country.fromNumeric(9999))
    }

    @Test
    fun `toJson and toString return alpha2 code`() {
        val c = Country.FR
        assertEquals("FR", c.toJson())
        assertEquals("FR", c.toString())
    }

    @Test
    fun `fromJson handles null and unknown values`() {
        assertEquals(Country.fromAlpha2("DE"), Country.fromJson("DE"))
        assertNull(Country.fromJson(null))
        assertNull(Country.fromJson("ZZ"))
    }

    // ── fromVatIdPrefix, the extracted shared parse ──────────────────

    @Test
    fun `fromVatIdPrefix reads the issuing country off a well-formed VAT id`() {
        assertEquals(Country.FR, Country.fromVatIdPrefix("FR73282932000"))
        assertEquals(Country.DE, Country.fromVatIdPrefix("DE123456789"))
    }

    @Test
    fun `fromVatIdPrefix maps the Greek VAT prefix EL to country GR`() {
        assertEquals(Country.GR, Country.fromVatIdPrefix("EL123456789"))
    }

    @Test
    fun `fromVatIdPrefix is case-insensitive and trims whitespace`() {
        assertEquals(Country.FR, Country.fromVatIdPrefix("  fr73282932000  "))
    }

    @Test
    fun `fromVatIdPrefix returns null for null, blank, or too-short input, never throws`() {
        assertNull(Country.fromVatIdPrefix(null))
        assertNull(Country.fromVatIdPrefix(""))
        assertNull(Country.fromVatIdPrefix("   "))
        assertNull(Country.fromVatIdPrefix("F"))
    }

    @Test
    fun `fromVatIdPrefix returns null for an unknown prefix instead of throwing`() {
        assertNull(Country.fromVatIdPrefix("ZZ123456789"))
    }
}
