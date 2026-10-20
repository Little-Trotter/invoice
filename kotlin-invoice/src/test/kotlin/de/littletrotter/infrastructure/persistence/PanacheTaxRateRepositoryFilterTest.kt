package de.littletrotter.infrastructure.persistence

import de.littletrotter.shared.country.Country
import de.littletrotter.infrastructure.persistence.PanacheTaxRateRepository.Companion.countryFilterFor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * pins the country-resolution half of the tax-rate filter. The former append/bind
 * string contract ("a `:countryEnum` placeholder appears exactly when a country gets bound")
 * became structural with the Criteria rewrite: predicates and their bind parameters are built
 * in one place, so an unbindable placeholder can no longer exist. What remains testable in
 * isolation is the resolution rule that decides whether the country clause participates.
 */
class PanacheTaxRateRepositoryFilterTest {

    @Test
    fun `a 2-char non-country filter stays name-only`() {
        assertNull(countryFilterFor("zz"))
    }

    @Test
    fun `a real country code resolves case-insensitively`() {
        assertEquals(Country.DE, countryFilterFor("de"))
        assertEquals(Country.DE, countryFilterFor("DE"))
    }

    @Test
    fun `blank and non-2-char inputs never resolve a country`() {
        assertNull(countryFilterFor(null))
        assertNull(countryFilterFor(""))
        assertNull(countryFilterFor("d"))
        assertNull(countryFilterFor("deu"))
    }
}
