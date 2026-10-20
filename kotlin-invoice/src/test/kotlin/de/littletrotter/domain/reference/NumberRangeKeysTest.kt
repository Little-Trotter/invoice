package de.littletrotter.domain.reference

import de.littletrotter.fixture.edit
import de.littletrotter.shared.numbering.ResetCycle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * N1 — prefix resolution and counter keys of [NumberRange], no database. Covers test
 * cases N1-03/04/05/08/15/16.
 */
class NumberRangeKeysTest {

    private val jan21 = LocalDate.of(2026, 1, 21)

    @Nested
    inner class ResolvePrefix {

        private fun range(source: PrefixSource, fixed: String? = null) = NumberRange().edit {
            prefixSource = source
            fixedPrefix = fixed
        }

        @Test
        fun `NONE yields no prefix even if the context carries one`() {
            val ctx = NumberContext(date = jan21, sellerPrefix = "DEVEDGE", dataFieldValue = "MUE")
            assertEquals(null, range(PrefixSource.NONE).resolvePrefix(ctx))
        }

        @Test
        fun `FIXED uses the configured prefix, not the context`() {
            val ctx = NumberContext(date = jan21, sellerPrefix = "DEVEDGE")
            assertEquals("KD", range(PrefixSource.FIXED, "KD").resolvePrefix(ctx))
        }

        @Test
        fun `SELLER_PROFILE reads sellerPrefix from the context`() {
            val ctx = NumberContext(date = jan21, sellerPrefix = "DEVEDGE")
            assertEquals("DEVEDGE", range(PrefixSource.SELLER_PROFILE).resolvePrefix(ctx))
        }

        @Test
        fun `FROM_DATA reads dataFieldValue from the context`() {
            val ctx = NumberContext(date = jan21, dataFieldValue = "MUE")
            assertEquals("MUE", range(PrefixSource.FROM_DATA).resolvePrefix(ctx))
        }
    }

    @Nested
    inner class ScopeAndPeriodKey {

        @Test
        fun `N1-15 - GLOBAL uses a single fixed key regardless of seller`() {
            val range = NumberRange().edit { scope = CounterScope.GLOBAL }
            assertEquals("*", range.scopeKey(NumberContext(date = jan21, sellerId = 1L)))
            assertEquals("*", range.scopeKey(NumberContext(date = jan21, sellerId = 2L)))
        }

        @Test
        fun `N1-16 - PER_SELLER produces a distinct key per seller`() {
            val range = NumberRange().edit { scope = CounterScope.PER_SELLER }
            val a = range.scopeKey(NumberContext(date = jan21, sellerId = 1L))
            val b = range.scopeKey(NumberContext(date = jan21, sellerId = 2L))
            assertEquals("1", a)
            assertEquals("2", b)
        }

        @Test
        fun `PER_SELLER without a sellerId in the context is a programming error, not silently GLOBAL`() {
            val range = NumberRange().edit { scope = CounterScope.PER_SELLER; code = "X" }
            assertThrows(IllegalArgumentException::class.java) {
                range.scopeKey(NumberContext(date = jan21))
            }
        }

        @Test
        fun `NEVER resets to an empty period key`() {
            val range = NumberRange().edit { resetCycle = ResetCycle.NEVER }
            assertEquals("", range.periodKey(NumberContext(date = jan21)))
        }

        @Test
        fun `YEARLY uses the year, independent of the number's own date granularity`() {
            val range = NumberRange().edit { resetCycle = ResetCycle.YEARLY }
            assertEquals("2026", range.periodKey(NumberContext(date = jan21)))
            assertEquals("2027", range.periodKey(NumberContext(date = LocalDate.of(2027, 1, 1))))
        }
    }
}
