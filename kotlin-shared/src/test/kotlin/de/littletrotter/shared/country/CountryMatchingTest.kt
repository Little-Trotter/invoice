package de.littletrotter.shared.country

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * COV — [Country.matching] had zero direct coverage before this AP (the free-text country
 * search resolver backing filter text on the enum-as-smallint `country` column, see its own
 * KDoc); [Country.fromNumeric] had one covered branch (found) but never its `null` fallback.
 */
class CountryMatchingTest {

    @Test
    fun `matching findet Laender deren alpha2-Code den Suchtext enthaelt`() {
        val result = Country.matching("de")

        assertTrue(result.contains(Country.DE))
    }

    @Test
    fun `matching ist case-insensitive`() {
        assertEquals(Country.matching("DE"), Country.matching("de"))
    }

    @Test
    fun `matching liefert eine leere Liste fuer leeren oder fehlenden Suchtext`() {
        assertTrue(Country.matching(null).isEmpty())
        assertTrue(Country.matching("").isEmpty())
        assertTrue(Country.matching("   ").isEmpty())
    }

    @Test
    fun `matching liefert eine leere Liste wenn kein alpha2-Code passt`() {
        assertTrue(Country.matching("zzz-kein-treffer").isEmpty())
    }

    @Test
    fun `fromNumeric findet das Land ueber seinen ISO-Zahlencode`() {
        assertEquals(Country.DE, Country.fromNumeric(276))
    }

    @Test
    fun `fromNumeric liefert null fuer einen unbekannten oder fehlenden Zahlencode`() {
        assertNull(Country.fromNumeric(null))
        assertNull(Country.fromNumeric(-1))
    }
}
