package de.littletrotter.domain.reference

import de.littletrotter.fixture.edit
import de.littletrotter.shared.numbering.DatePart
import de.littletrotter.shared.numbering.NumberFormatter as SharedNumberFormatter
import de.littletrotter.shared.numbering.NumberPattern
import de.littletrotter.shared.numbering.ResetCycle

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.shared.ShortHash
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** N1 — reine, DB-freie Regeln: Entitäts-validierung, Formatierung, Präfix-Ableitung,
 *  ShortHash-Alphabet. Die Zähler-/Vergabesemantik aus den Service-/Infrastruktur-Klassen bleibt
 *  eigenen (Integration-)Tests vorbehalten. */
class NumberRangeTest {

    private fun validRange() = NumberRange().edit {
        code = "CUSTOMER_DEFAULT"
        displayName = "Kundennummer"
        subject = NumberRangeSubject.CUSTOMER
        startValue = 1
        counterWidth = 5
        continuity = Continuity.UNIQUE_ONLY
    }

    @Test
    fun `boundary - createdAt is settable and defaults to null on a new instance`() {
        val range = validRange()
        assertNull(range.createdAt)

        val stamp = java.time.OffsetDateTime.parse("2026-03-14T10:00:00Z")
        range.edit { createdAt = stamp }

        assertEquals(stamp, range.createdAt)
    }

    @Test
    fun `counterWidth 5 bei Stand 42 ergibt 00042`() {
        val s = SharedNumberFormatter.compose(NumberPattern(null, DatePart.NONE, 5), LocalDate.of(2026, 8, 21), 42)
        assertEquals("00042", s)
    }

    @Test
    fun `counter exceeding the configured width grows wider instead of being truncated`() {
        val s = SharedNumberFormatter.compose(NumberPattern(null, DatePart.NONE, 5), LocalDate.of(2026, 8, 21), 100000)
        assertEquals("100000", s)
    }

    @Test
    fun `fixedPrefix ohne fixedPrefix wird abgewiesen`() {
        val range = validRange().edit { prefixSource = PrefixSource.FIXED }
        assertThrows(InvalidParameterException::class.java) { range.validate() }
    }

    @Test
    fun `YEARLY ohne Datumsanteil wird abgewiesen`() {
        val range = validRange().edit { resetCycle = ResetCycle.YEARLY; datePart = DatePart.NONE }
        assertThrows(InvalidParameterException::class.java) { range.validate() }
    }

    @Test
    fun `FROM_DATA ohne Quellfeld wird abgewiesen`() {
        val range = validRange().edit { prefixSource = PrefixSource.FROM_DATA; prefixDataField = null }
        assertThrows(InvalidParameterException::class.java) { range.validate() }
    }

    @Test
    fun `negativer Startwert wird abgewiesen`() {
        val range = validRange().edit { startValue = -1 }
        assertThrows(InvalidParameterException::class.java) { range.validate() }
    }

    @Test
    fun `gueltiger Kreis validiert ohne Wurf`() {
        validRange().edit {
            prefixSource = PrefixSource.FROM_DATA
            prefixDataField = PrefixDataField.NAME_INITIALS
        }.validate()
    }

    @Test
    fun `NAME_INITIALS leitet die ersten drei Buchstaben ab`() {
        assertEquals("MUE", NumberPrefixDeriver.fromData(PrefixDataField.NAME_INITIALS, "Müller GmbH"))
    }

    @Test
    fun `FROM_DATA leeres Quellfeld ergibt keinen Platzhalter`() {
        assertNull(NumberPrefixDeriver.fromData(PrefixDataField.NAME_INITIALS, "  "))
        assertNull(NumberPrefixDeriver.fromData(PrefixDataField.CATALOGUE_CATEGORY, null))
    }

    @Test
    fun `CATALOGUE_CATEGORY leitet eine Kurzform ab`() {
        assertEquals("SVC", NumberPrefixDeriver.fromData(PrefixDataField.CATALOGUE_CATEGORY, "SERVICE"))
    }

    @Test
    fun `SELLER_PROFILE loest aus dem Kontext und FROM_DATA aus dem Datenfeld`() {
        val range = validRange().edit { prefixSource = PrefixSource.SELLER_PROFILE }
        val ctx = NumberContext(
            LocalDate.of(2026, 8, 21),
            sellerId = 5,
            sellerPrefix = "DEVEDGE",
            dataFieldValue = "MUE",
        )
        assertEquals("DEVEDGE", range.resolvePrefix(ctx))
        range.edit { prefixSource = PrefixSource.FROM_DATA }
        assertEquals("MUE", range.resolvePrefix(ctx))
        range.edit { prefixSource = PrefixSource.NONE }
        assertNull(range.resolvePrefix(ctx))
    }

    @Test
    fun `YEAR_MONTH baut den Datumsanteil mit Nullen auf und yeearly periodKey ist das Jahr`() {
        val date = LocalDate.of(2026, 8, 21)
        val range = validRange().edit { datePart = DatePart.YEAR_MONTH; resetCycle = ResetCycle.YEARLY }
        val s = SharedNumberFormatter.compose(NumberPattern("DEVEDGE", range.datePart, 5), date, 42)
        assertEquals("DEVEDGE-2026-08-00042", s)
        assertEquals("2026", range.periodKey(NumberContext(date, sellerId = 1)))
    }

    @Test
    fun `ShortHash ist 10 Zeichen aus Crockford-Alphabet ohne ILUO`() {
        val hash = ShortHash.random()
        assertEquals(ShortHash.LENGTH, hash.length)
        assertTrue(hash.all { it in "0123456789ABCDEFGHJKMNPQRSTVWXYZ" })
        assertTrue(hash.none { it in ShortHash.excludedCharacters })
        assertNotNull(ShortHash.randomExcept(setOf()))
    }

    @Test
    fun `empty code is rejected`() {
        val range = validRange().edit { code = "" }
        assertThrows(InvalidParameterException::class.java) { range.validate() }
    }

    @Test
    fun `empty displayName is rejected`() {
        val range = validRange().edit { displayName = "" }
        assertThrows(InvalidParameterException::class.java) { range.validate() }
    }

    @Test
    fun `counterWidth less than 1 is rejected`() {
        val range = validRange().edit { counterWidth = 0 }
        assertThrows(InvalidParameterException::class.java) { range.validate() }
    }

    @Test
    fun `FIXED prefix with empty fixedPrefix is rejected`() {
        val range = validRange().edit {
            prefixSource = PrefixSource.FIXED
            fixedPrefix = ""
        }
        assertThrows(InvalidParameterException::class.java) { range.validate() }
    }

    @Test
    fun `FIXED prefix with null fixedPrefix is rejected`() {
        val range = validRange().edit {
            prefixSource = PrefixSource.FIXED
            fixedPrefix = null
        }
        assertThrows(InvalidParameterException::class.java) { range.validate() }
    }

    @Test
    fun `FROM_DATA with null prefixDataField is rejected`() {
        val range = validRange().edit {
            prefixSource = PrefixSource.FROM_DATA
            prefixDataField = null
        }
        assertThrows(InvalidParameterException::class.java) { range.validate() }
    }

    @Test
    fun `fixedPrefix when prefixSource is not FIXED is rejected`() {
        val range = validRange().edit {
            prefixSource = PrefixSource.NONE
            fixedPrefix = "PREFIX"
        }
        assertThrows(InvalidParameterException::class.java) { range.validate() }
    }

    @Test
    fun `prefixDataField when prefixSource is not FROM_DATA is rejected`() {
        val range = validRange().edit {
            prefixSource = PrefixSource.NONE
            prefixDataField = PrefixDataField.NAME_INITIALS
        }
        assertThrows(InvalidParameterException::class.java) { range.validate() }
    }

    @Test
    fun `YEARLY reset cycle with YEAR date part is accepted`() {
        val range = validRange().edit {
            resetCycle = ResetCycle.YEARLY
            datePart = DatePart.YEAR
        }
        range.validate() // must not throw
    }

    @Test
    fun `YEARLY reset cycle with YEAR_MONTH date part is accepted`() {
        val range = validRange().edit {
            resetCycle = ResetCycle.YEARLY
            datePart = DatePart.YEAR_MONTH
        }
        range.validate() // must not throw
    }

    @Test
    fun `YEARLY reset cycle with YEAR_MONTH_DAY date part is accepted`() {
        val range = validRange().edit {
            resetCycle = ResetCycle.YEARLY
            datePart = DatePart.YEAR_MONTH_DAY
        }
        range.validate() // must not throw
    }

    @Test
    fun `NEVER reset cycle with NONE date part is accepted`() {
        val range = validRange().edit {
            resetCycle = ResetCycle.NEVER
            datePart = DatePart.NONE
        }
        range.validate() // must not throw
    }

    @Test
    fun `zero startValue is accepted`() {
        val range = validRange().edit { startValue = 0 }
        range.validate() // must not throw
    }

    @Test
    fun `large startValue is accepted`() {
        val range = validRange().edit { startValue = Long.MAX_VALUE }
        range.validate() // must not throw
    }

    @Test
    fun `whitespace-only code is rejected`() {
        val range = validRange().edit { code = "   " }
        assertThrows(InvalidParameterException::class.java) { range.validate() }
    }

    @Test
    fun `whitespace-only displayName is rejected`() {
        val range = validRange().edit { displayName = "   " }
        assertThrows(InvalidParameterException::class.java) { range.validate() }
    }

    @Test
    fun `FIXED prefix with whitespace-only fixedPrefix is rejected`() {
        val range = validRange().edit {
            prefixSource = PrefixSource.FIXED
            fixedPrefix = "   "
        }
        assertThrows(InvalidParameterException::class.java) { range.validate() }
    }
}
