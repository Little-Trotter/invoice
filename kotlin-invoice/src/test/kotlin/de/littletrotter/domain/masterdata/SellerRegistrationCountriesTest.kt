package de.littletrotter.domain.masterdata

import de.littletrotter.shared.country.Country
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * anbieter-einvoice-ergonomie §3/§7 — [sellerRegistrationCountries]'s branches: `vatId`
 * null/blank/unparseable/DE/FR, with and without additional registrations.
 */
class SellerRegistrationCountriesTest {

    @Test
    fun `null vatId and no registrations yields an empty set`() {
        assertEquals(emptySet<Country>(), sellerRegistrationCountries(null, emptySet()))
    }

    @Test
    fun `blank vatId contributes nothing`() {
        assertEquals(emptySet<Country>(), sellerRegistrationCountries("   ", emptySet()))
    }

    @Test
    fun `an unparseable (too short) vatId contributes nothing, never throws`() {
        assertEquals(emptySet<Country>(), sellerRegistrationCountries("D", emptySet()))
    }

    @Test
    fun `a German vatId contributes DE`() {
        assertEquals(setOf(Country.DE), sellerRegistrationCountries("DE123456789", emptySet()))
    }

    @Test
    fun `a French vatId contributes FR`() {
        assertEquals(setOf(Country.FR), sellerRegistrationCountries("FR12345678901", emptySet()))
    }

    @Test
    fun `additional registrations are unioned with the vatId's own country`() {
        assertEquals(
            setOf(Country.DE, Country.LU, Country.BE),
            sellerRegistrationCountries("DE123456789", setOf(Country.LU, Country.BE)),
        )
    }

    @Test
    fun `a blank vatId with additional registrations yields just the registrations`() {
        assertEquals(setOf(Country.FR), sellerRegistrationCountries(null, setOf(Country.FR)))
    }

    @Test
    fun `a vatId country already present among the additional registrations does not duplicate`() {
        assertEquals(setOf(Country.FR), sellerRegistrationCountries("FR12345678901", setOf(Country.FR)))
    }
}
