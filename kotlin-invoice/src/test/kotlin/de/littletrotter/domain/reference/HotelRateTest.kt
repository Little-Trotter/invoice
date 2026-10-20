package de.littletrotter.domain.reference

import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** R2 — HotelRate had no domain methods at all before (region, isApplicableFor); parity with
 *  PerDiemRate. */
class HotelRateTest {

    @Test
    fun `matchesRegion handles blanks and case-insensitive comparisons`() {
        val global = HotelRate().edit { region = null }
        val paris = HotelRate().edit { region = " paris " }

        assertTrue(global.matchesRegion("London"))
        assertTrue(paris.matchesRegion("PARIS"))
        assertTrue(paris.matchesRegion(" paris "))
        assertFalse(paris.matchesRegion("Lyon"))
    }

    @Test
    fun `isApplicableFor uses country, normalized region matching and validity window`() {
        val rate = HotelRate().edit {
            country = Country.FR
            region = "Paris"
            validFrom = LocalDate.of(2026, 1, 1)
        }

        assertTrue(rate.isApplicableFor(Country.FR, "Paris", LocalDate.of(2026, 6, 1)))
        assertFalse(rate.isApplicableFor(Country.FR, "Lyon", LocalDate.of(2026, 6, 1)))
        assertFalse(rate.isApplicableFor(Country.DE, "Paris", LocalDate.of(2026, 6, 1)))
        assertFalse(rate.isApplicableFor(Country.FR, "Paris", LocalDate.of(2025, 12, 31)))
    }

    @Test
    fun `an inactive row is never applicable even when country, region and date match`() {
        val deactivated = HotelRate().edit {
            country = Country.IT; region = "Rom"; isActive = false
            validFrom = LocalDate.of(2024, 1, 1)
        }
        assertFalse(deactivated.isApplicableFor(Country.IT, "Rom", LocalDate.of(2026, 6, 1)))
    }

    @Test
    fun `matchesRegion falls back to the country catch-all when no region is requested`() {
        val paris = HotelRate().edit { region = "Paris" }
        assertFalse(paris.matchesRegion(null))
    }

    @Test
    fun `isApplicableFor is applicable from the very beginning when validFrom is unset`() {
        val rate = HotelRate().edit { country = Country.FR; region = "Paris"; validFrom = null }
        assertTrue(rate.isApplicableFor(Country.FR, "Paris", LocalDate.of(2000, 1, 1)))
    }

    @Test
    fun `isApplicableFor stops once validTo has passed`() {
        val rate = HotelRate().edit {
            country = Country.FR
            region = "Paris"
            validFrom = LocalDate.of(2020, 1, 1)
            validTo = LocalDate.of(2025, 12, 31)
        }
        assertTrue(rate.isApplicableFor(Country.FR, "Paris", LocalDate.of(2025, 12, 31)))
        assertFalse(rate.isApplicableFor(Country.FR, "Paris", LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun `matchesRegion with both non-blank and equal values returns true`() {
        val rate = HotelRate().edit { region = "Paris" }
        assertTrue(rate.matchesRegion("paris"))
    }

    @Test
    fun `matchesRegion with both non-blank and unequal values returns false`() {
        val rate = HotelRate().edit { region = "Paris" }
        assertFalse(rate.matchesRegion("London"))
    }

    @Test
    fun `isApplicableFor with inactive rate returns false regardless of other conditions`() {
        val rate = HotelRate().edit {
            country = Country.FR
            region = "Paris"
            validFrom = LocalDate.of(2020, 1, 1)
            isActive = false
        }
        assertFalse(rate.isApplicableFor(Country.FR, "Paris", LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun `isApplicableFor with wrong country returns false`() {
        val rate = HotelRate().edit {
            country = Country.DE
            region = "Berlin"
            isActive = true
        }
        assertFalse(rate.isApplicableFor(Country.FR, "Berlin", LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun `isApplicableFor with wrong region returns false`() {
        val rate = HotelRate().edit {
            country = Country.FR
            region = "Paris"
            isActive = true
        }
        assertFalse(rate.isApplicableFor(Country.FR, "Lyon", LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun `isApplicableFor with date before validFrom returns false`() {
        val rate = HotelRate().edit {
            country = Country.FR
            region = "Paris"
            validFrom = LocalDate.of(2025, 1, 1)
            isActive = true
        }
        assertFalse(rate.isApplicableFor(Country.FR, "Paris", LocalDate.of(2024, 12, 31)))
    }

    @Test
    fun `matchesRegion with empty and empty string returns true`() {
        val rate = HotelRate().edit { region = null }
        assertTrue(rate.matchesRegion(""))
    }

    @Test
    fun `matchesRegion with whitespace only region and empty request returns true`() {
        val rate = HotelRate().edit { region = "   " }
        assertTrue(rate.matchesRegion(""))
    }

    @Test
    fun `boundary - createdAt and version accessors round-trip independently of the domain validity rules`() {
        val rate = HotelRate()
        val timestamp = OffsetDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC)

        rate.edit { createdAt = timestamp }
        rate.edit { version = 7L }

        assertEquals(timestamp, rate.createdAt)
        assertEquals(7L, rate.version)
    }
}
