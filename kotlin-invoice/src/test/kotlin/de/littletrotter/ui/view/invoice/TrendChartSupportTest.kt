package de.littletrotter.ui.view.invoice

import de.littletrotter.application.dto.DailyTrend
import de.littletrotter.application.dto.MonthlyTrend
import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Z2 — [TrendChartSupport]'s helpers moved out of `InvoiceStatisticsView` as part of the
 * statistics-view decomposition; this is the first time they get direct unit test coverage
 * (previously only exercised indirectly through the monolithic view).
 */
class TrendChartSupportTest {

    // ─── fillMissingMonths ─────────────────────────────────────────────────

    @Test
    fun `fillMissingMonths keeps existing months untouched and adds zero rows for the rest`() {
        val existing = listOf(
            MonthlyTrend(year = 2025, month = 2, netAmount = Money.of(BigDecimal("100.00")), grossAmount = Money.of(BigDecimal("119.00"))),
        )

        val filled = TrendChartSupport.fillMissingMonths(existing, 1..3, year = 2025)

        assertEquals(3, filled.size)
        assertEquals(MonthlyTrend(2025, 1, Money.ZERO, Money.ZERO), filled[0])
        assertEquals(existing[0], filled[1])
        assertEquals(MonthlyTrend(2025, 3, Money.ZERO, Money.ZERO), filled[2])
    }

    @Test
    fun `fillMissingMonths with fully empty input produces one zero row per month in range`() {
        val filled = TrendChartSupport.fillMissingMonths(emptyList(), 4..6, year = 2030)

        assertEquals(listOf(4, 5, 6), filled.map { it.month })
        assertEquals(listOf(2030, 2030, 2030), filled.map { it.year })
        assertTrue(filled.all { it.netAmount == Money.ZERO && it.grossAmount == Money.ZERO })
    }

    @Test
    fun `fillMissingMonths with a fully covered range returns the existing rows unchanged`() {
        val existing = (1..12).map {
            MonthlyTrend(year = 2024, month = it, netAmount = Money.of(BigDecimal(it)), grossAmount = Money.of(BigDecimal(it * 2)))
        }

        val filled = TrendChartSupport.fillMissingMonths(existing, 1..12, year = 2024)

        assertEquals(existing, filled)
    }

    // ─── fillMissingDays ────────────────────────────────────────────────────

    @Test
    fun `fillMissingDays fills every day of a 30-day month`() {
        val existing = listOf(
            DailyTrend(year = 2025, month = 4, day = 15, netAmount = Money.of(BigDecimal("50.00")), grossAmount = Money.of(BigDecimal("59.50"))),
        )

        val filled = TrendChartSupport.fillMissingDays(existing, year = 2025, month = 4)

        assertEquals(30, filled.size, "April has 30 days")
        assertEquals(existing[0], filled[14], "day 15 is index 14")
        assertEquals(DailyTrend(2025, 4, 1, Money.ZERO, Money.ZERO), filled[0])
        assertEquals(DailyTrend(2025, 4, 30, Money.ZERO, Money.ZERO), filled[29])
    }

    @Test
    fun `fillMissingDays respects February in a leap year`() {
        val filled = TrendChartSupport.fillMissingDays(emptyList(), year = 2024, month = 2)
        assertEquals(29, filled.size, "2024 is a leap year")
    }

    @Test
    fun `fillMissingDays respects February in a non-leap year`() {
        val filled = TrendChartSupport.fillMissingDays(emptyList(), year = 2025, month = 2)
        assertEquals(28, filled.size)
    }

    // ─── shortMonth / quarterOf ─────────────────────────────────────────────

    @Test
    fun `shortMonth returns the German three-letter abbreviation`() {
        assertEquals("Jan.", TrendChartSupport.shortMonth(1))
        assertEquals("Dez.", TrendChartSupport.shortMonth(12))
    }

    @Test
    fun `quarterOf maps every month to its calendar quarter`() {
        assertEquals(1, TrendChartSupport.quarterOf(1))
        assertEquals(1, TrendChartSupport.quarterOf(3))
        assertEquals(2, TrendChartSupport.quarterOf(4))
        assertEquals(2, TrendChartSupport.quarterOf(6))
        assertEquals(3, TrendChartSupport.quarterOf(7))
        assertEquals(3, TrendChartSupport.quarterOf(9))
        assertEquals(4, TrendChartSupport.quarterOf(10))
        assertEquals(4, TrendChartSupport.quarterOf(12))
    }
}
