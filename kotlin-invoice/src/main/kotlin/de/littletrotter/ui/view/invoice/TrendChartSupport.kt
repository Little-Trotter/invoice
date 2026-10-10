package de.littletrotter.ui.view.invoice

import de.littletrotter.application.dto.DailyTrend
import de.littletrotter.application.dto.MonthlyTrend
import de.littletrotter.shared.Money
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/**
 * Pure helpers behind the trend chart model, split out so they're trivially unit-testable without a
 * Vaadin UI/session (no method here touches a `Component`).
 */
private const val MONTHS_PER_QUARTER = 3

internal object TrendChartSupport {

    val quarterLabels = mapOf(
        1 to "I (Jan–März)", 2 to "II (Apr–Juni)", 3 to "III (Jul–Sep)", 4 to "IV (Okt–Dez)",
    )

    fun shortMonth(month: Int): String = Month.of(month).getDisplayName(TextStyle.SHORT, Locale.GERMAN)
    fun quarterOf(month: Int): Int = (month - 1) / MONTHS_PER_QUARTER + 1

    /** Fill in months that have no data yet (zero amounts) so the chart shows all periods. */
    fun fillMissingMonths(existing: List<MonthlyTrend>, monthRange: IntRange, year: Int): List<MonthlyTrend> {
        val byMonth = existing.associateBy { it.month }
        return monthRange.map { m ->
            byMonth[m] ?: MonthlyTrend(year = year, month = m, netAmount = Money.ZERO, grossAmount = Money.ZERO)
        }
    }

    /** Fill in days that have no data yet (zero amounts) so the chart shows all calendar days. */
    fun fillMissingDays(existing: List<DailyTrend>, year: Int, month: Int): List<DailyTrend> {
        val byDay = existing.associateBy { it.day }
        val daysInMonth = LocalDate.of(year, month, 1).lengthOfMonth()
        return (1..daysInMonth).map { day ->
            byDay[day] ?: DailyTrend(year = year, month = month, day = day, netAmount = Money.ZERO, grossAmount = Money.ZERO)
        }
    }
}
