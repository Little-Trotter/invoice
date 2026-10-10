package de.littletrotter.shared.numbering

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate

class NumberFormatterTest {

    private val jan21 = LocalDate.of(2026, 1, 21)
    private val aug21 = LocalDate.of(2026, 8, 21)

    @Nested
    inner class Compose {

        @Test
        fun `N1-03 - width 5 pads with zeros`() {
            assertEquals("00042", NumberFormatter.compose(NumberPattern(null, DatePart.NONE, 5), jan21, 42))
        }

        @Test
        fun `N1-04 - the counter grows wider instead of being truncated`() {
            assertEquals("100000", NumberFormatter.compose(NumberPattern(null, DatePart.NONE, 5), jan21, 100_000))
        }

        @Test
        fun `N1-05 - a chosen start value appears unchanged in the first number`() {
            assertEquals("04712", NumberFormatter.compose(NumberPattern(null, DatePart.NONE, 5), jan21, 4712))
        }

        @Test
        fun `a blank prefix is omitted, not rendered as an empty segment`() {
            assertEquals("00001", NumberFormatter.compose(NumberPattern("   ", DatePart.NONE, 5), jan21, 1))
        }

        @Test
        fun `date part NONE contributes nothing`() {
            assertEquals("PRE-00001", NumberFormatter.compose(NumberPattern("PRE", DatePart.NONE, 5), jan21, 1))
        }

        @Test
        fun `date part YEAR`() {
            assertEquals("PRE-2026-00001", NumberFormatter.compose(NumberPattern("PRE", DatePart.YEAR, 5), jan21, 1))
        }

        @Test
        fun `date part YEAR_MONTH`() {
            assertEquals("PRE-2026-01-00001", NumberFormatter.compose(NumberPattern("PRE", DatePart.YEAR_MONTH, 5), jan21, 1))
        }

        @Test
        fun `date part YEAR_MONTH_DAY carries the day but does not reset the counter`() {
            assertEquals(
                "DEVEDGE-2026-08-21-04712",
                NumberFormatter.compose(NumberPattern("DEVEDGE", DatePart.YEAR_MONTH_DAY, 5), aug21, 4712),
            )
            // N2-06 — the next day does not reset the counter; the format only suggests that it does.
            assertEquals(
                "DEVEDGE-2026-08-22-04713",
                NumberFormatter.compose(NumberPattern("DEVEDGE", DatePart.YEAR_MONTH_DAY, 5), aug21.plusDays(1), 4713),
            )
        }
    }

    @Nested
    inner class PeriodKey {

        @Test
        fun `NEVER resets to an empty period key`() {
            assertEquals("", NumberFormatter.periodKey(ResetCycle.NEVER, jan21))
        }

        @Test
        fun `YEARLY uses the year`() {
            assertEquals("2026", NumberFormatter.periodKey(ResetCycle.YEARLY, jan21))
        }

        @Test
        fun `YEARLY handles year boundary`() {
            assertEquals("2027", NumberFormatter.periodKey(ResetCycle.YEARLY, LocalDate.of(2027, 1, 1)))
        }
    }

    @Nested
    inner class EnumEntries {

        @Test
        fun `DatePart entries are ordered as expected`() {
            assertEquals(listOf(DatePart.NONE, DatePart.YEAR, DatePart.YEAR_MONTH, DatePart.YEAR_MONTH_DAY), DatePart.entries)
        }

        @Test
        fun `ResetCycle entries are ordered as expected`() {
            assertEquals(listOf(ResetCycle.NEVER, ResetCycle.YEARLY), ResetCycle.entries)
        }
    }
}
