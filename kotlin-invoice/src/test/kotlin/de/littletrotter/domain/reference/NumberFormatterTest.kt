package de.littletrotter.domain.reference

import de.littletrotter.shared.numbering.DatePart
import de.littletrotter.shared.numbering.NumberFormatter as SharedNumberFormatter
import de.littletrotter.shared.numbering.NumberPattern
import de.littletrotter.shared.numbering.ResetCycle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * N1 — pure formatting ([NumberFormatter]) and prefix resolution, no database. Covers test
 * cases N1-03/04/05/08/15/16.
 */
class NumberFormatterTest {

    private val jan21 = LocalDate.of(2026, 1, 21)
    private val aug21 = LocalDate.of(2026, 8, 21)

    @Nested
    inner class Compose {

        @Test
        fun `N1-03 - width 5 pads with zeros`() {
            assertEquals("00042", SharedNumberFormatter.compose(NumberPattern(null, DatePart.NONE, 5), jan21, 42))
        }

        @Test
        fun `N1-04 - the counter grows wider instead of being truncated`() {
            assertEquals("100000", SharedNumberFormatter.compose(NumberPattern(null, DatePart.NONE, 5), jan21, 100_000))
        }

        @Test
        fun `N1-05 - a chosen start value appears unchanged in the first number`() {
            assertEquals("04712", SharedNumberFormatter.compose(NumberPattern(null, DatePart.NONE, 5), jan21, 4712))
        }

        @Test
        fun `a blank prefix is omitted, not rendered as an empty segment`() {
            assertEquals("00001", SharedNumberFormatter.compose(NumberPattern("   ", DatePart.NONE, 5), jan21, 1))
        }

        @Test
        fun `date part NONE contributes nothing`() {
            assertEquals("PRE-00001", SharedNumberFormatter.compose(NumberPattern("PRE", DatePart.NONE, 5), jan21, 1))
        }

        @Test
        fun `date part YEAR`() {
            assertEquals("PRE-2026-00001", SharedNumberFormatter.compose(NumberPattern("PRE", DatePart.YEAR, 5), jan21, 1))
        }

        @Test
        fun `date part YEAR_MONTH`() {
            assertEquals("PRE-2026-01-00001", SharedNumberFormatter.compose(NumberPattern("PRE", DatePart.YEAR_MONTH, 5), jan21, 1))
        }

        @Test
        fun `date part YEAR_MONTH_DAY carries the day but does not reset the counter`() {
            assertEquals(
                "DEVEDGE-2026-08-21-04712",
                SharedNumberFormatter.compose(NumberPattern("DEVEDGE", DatePart.YEAR_MONTH_DAY, 5), aug21, 4712),
            )
            // N2-06 — the next day does not reset the counter; the format only suggests that it does.
            assertEquals(
                "DEVEDGE-2026-08-22-04713",
                SharedNumberFormatter.compose(NumberPattern("DEVEDGE", DatePart.YEAR_MONTH_DAY, 5), aug21.plusDays(1), 4713),
            )
        }
    }

    @Nested
    inner class ResolvePrefix {

        private fun range(source: PrefixSource, fixed: String? = null) = NumberRange().apply {
            prefixSource = source
            fixedPrefix = fixed
        }

        @Test
        fun `NONE yields no prefix even if the context carries one`() {
            val ctx = NumberContext(date = jan21, sellerPrefix = "DEVEDGE", dataFieldValue = "MUE")
            assertEquals(null, NumberFormatter.resolvePrefix(range(PrefixSource.NONE), ctx))
        }

        @Test
        fun `FIXED uses the configured prefix, not the context`() {
            val ctx = NumberContext(date = jan21, sellerPrefix = "DEVEDGE")
            assertEquals("KD", NumberFormatter.resolvePrefix(range(PrefixSource.FIXED, "KD"), ctx))
        }

        @Test
        fun `SELLER_PROFILE reads sellerPrefix from the context`() {
            val ctx = NumberContext(date = jan21, sellerPrefix = "DEVEDGE")
            assertEquals("DEVEDGE", NumberFormatter.resolvePrefix(range(PrefixSource.SELLER_PROFILE), ctx))
        }

        @Test
        fun `FROM_DATA reads dataFieldValue from the context`() {
            val ctx = NumberContext(date = jan21, dataFieldValue = "MUE")
            assertEquals("MUE", NumberFormatter.resolvePrefix(range(PrefixSource.FROM_DATA), ctx))
        }
    }

    @Nested
    inner class ScopeAndPeriodKey {

        @Test
        fun `N1-15 - GLOBAL uses a single fixed key regardless of seller`() {
            val range = NumberRange().apply { scope = CounterScope.GLOBAL }
            assertEquals("*", NumberFormatter.scopeKey(range, NumberContext(date = jan21, sellerId = 1L)))
            assertEquals("*", NumberFormatter.scopeKey(range, NumberContext(date = jan21, sellerId = 2L)))
        }

        @Test
        fun `N1-16 - PER_SELLER produces a distinct key per seller`() {
            val range = NumberRange().apply { scope = CounterScope.PER_SELLER }
            val a = NumberFormatter.scopeKey(range, NumberContext(date = jan21, sellerId = 1L))
            val b = NumberFormatter.scopeKey(range, NumberContext(date = jan21, sellerId = 2L))
            assertEquals("1", a)
            assertEquals("2", b)
        }

        @Test
        fun `PER_SELLER without a sellerId in the context is a programming error, not silently GLOBAL`() {
            val range = NumberRange().apply { scope = CounterScope.PER_SELLER; code = "X" }
            assertThrows(IllegalArgumentException::class.java) {
                NumberFormatter.scopeKey(range, NumberContext(date = jan21))
            }
        }

        @Test
        fun `NEVER resets to an empty period key`() {
            val range = NumberRange().apply { resetCycle = ResetCycle.NEVER }
            assertEquals("", NumberFormatter.periodKey(range, NumberContext(date = jan21)))
        }

        @Test
        fun `YEARLY uses the year, independent of the number's own date granularity`() {
            val range = NumberRange().apply { resetCycle = ResetCycle.YEARLY }
            assertEquals("2026", NumberFormatter.periodKey(range, NumberContext(date = jan21)))
            assertEquals("2027", NumberFormatter.periodKey(range, NumberContext(date = LocalDate.of(2027, 1, 1))))
        }
    }
}
