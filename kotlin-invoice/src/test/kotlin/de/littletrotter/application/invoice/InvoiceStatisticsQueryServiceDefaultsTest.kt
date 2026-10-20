package de.littletrotter.application.invoice

import de.littletrotter.application.dto.ArticleTotals
import de.littletrotter.application.dto.DailyTrend
import de.littletrotter.application.dto.MonthlyTotals
import de.littletrotter.application.dto.MonthlyTrend
import de.littletrotter.application.dto.QuarterlyTotals
import de.littletrotter.application.dto.RecipientTotals
import de.littletrotter.application.dto.SenderTotals
import de.littletrotter.application.dto.StatisticsFilter
import de.littletrotter.application.dto.StatusDistribution
import de.littletrotter.application.dto.YearlyTotals
import de.littletrotter.application.invoice.port.inbound.InvoiceStatisticsQueryPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The `filter`/`limit` default values are declared on [InvoiceStatisticsQueryPort] itself, not on
 * the implementation's overrides (Kotlin forbids repeating a default on an override), so they only
 * compile into call sites that omit the argument. Real callers always pass an explicit filter;
 * these tests exercise the default-value bridges.
 */
class InvoiceStatisticsQueryServiceDefaultsTest {

    private class RecordingService : InvoiceStatisticsQueryPort {
        val calls = mutableListOf<Pair<String, StatisticsFilter>>()

        override fun getYearlyTotals(filter: StatisticsFilter): List<YearlyTotals> {
            calls += "yearly" to filter; return emptyList()
        }
        override fun getMonthlyTotals(year: Int, filter: StatisticsFilter): List<MonthlyTotals> {
            calls += "monthly" to filter; return emptyList()
        }
        override fun getQuarterlyTotals(year: Int, filter: StatisticsFilter): List<QuarterlyTotals> {
            calls += "quarterly" to filter; return emptyList()
        }
        override fun getRecipientTotals(limit: Int, filter: StatisticsFilter): List<RecipientTotals> {
            calls += "recipientTotals" to filter; return emptyList()
        }
        override fun getSenderTotals(limit: Int, filter: StatisticsFilter): List<SenderTotals> {
            calls += "senderTotals" to filter; return emptyList()
        }
        override fun getArticleTotals(limit: Int, filter: StatisticsFilter): List<ArticleTotals> {
            calls += "articleTotals" to filter; return emptyList()
        }
        override fun getMonthlyTrend(year: Int, filter: StatisticsFilter): List<MonthlyTrend> {
            calls += "monthlyTrend" to filter; return emptyList()
        }
        override fun getDailyTrend(year: Int, month: Int, filter: StatisticsFilter): List<DailyTrend> {
            calls += "dailyTrend" to filter; return emptyList()
        }
        override fun getStatusDistribution(filter: StatisticsFilter): List<StatusDistribution> {
            calls += "statusDistribution" to filter; return emptyList()
        }
        override fun getAvailableYears(filter: StatisticsFilter): List<Int> {
            calls += "years" to filter; return emptyList()
        }
    }

    @Test
    fun `boundary - every statistics getter defaults its omitted filter to StatisticsFilter EMPTY`() {
        val service = RecordingService()

        service.getYearlyTotals()
        service.getMonthlyTotals(2026)
        service.getQuarterlyTotals(2026)
        service.getRecipientTotals()
        service.getSenderTotals()
        service.getArticleTotals()
        service.getMonthlyTrend(2026)
        service.getDailyTrend(2026, 8)
        service.getStatusDistribution()
        service.getAvailableYears()

        val e = StatisticsFilter.EMPTY
        assertEquals(
            listOf(
                "yearly" to e,
                "monthly" to e,
                "quarterly" to e,
                "recipientTotals" to e,
                "senderTotals" to e,
                "articleTotals" to e,
                "monthlyTrend" to e,
                "dailyTrend" to e,
                "statusDistribution" to e,
                "years" to e,
            ),
            service.calls,
        )
    }

    @Test
    fun `boundary - getRecipientTotals also defaults the omitted limit to 10`() {
        val service = object : InvoiceStatisticsQueryPort {
            var capturedLimit: Int? = null
            override fun getYearlyTotals(filter: StatisticsFilter) = emptyList<YearlyTotals>()
            override fun getMonthlyTotals(year: Int, filter: StatisticsFilter) = emptyList<MonthlyTotals>()
            override fun getQuarterlyTotals(year: Int, filter: StatisticsFilter) = emptyList<QuarterlyTotals>()
            override fun getRecipientTotals(limit: Int, filter: StatisticsFilter): List<RecipientTotals> {
                capturedLimit = limit
                return emptyList()
            }
            override fun getSenderTotals(limit: Int, filter: StatisticsFilter) = emptyList<SenderTotals>()
            override fun getArticleTotals(limit: Int, filter: StatisticsFilter) = emptyList<ArticleTotals>()
            override fun getMonthlyTrend(year: Int, filter: StatisticsFilter) = emptyList<MonthlyTrend>()
            override fun getDailyTrend(year: Int, month: Int, filter: StatisticsFilter) = emptyList<DailyTrend>()
            override fun getStatusDistribution(filter: StatisticsFilter) = emptyList<StatusDistribution>()
            override fun getAvailableYears(filter: StatisticsFilter) = emptyList<Int>()
        }

        service.getRecipientTotals()

        assertEquals(10, service.capturedLimit)
    }
}
