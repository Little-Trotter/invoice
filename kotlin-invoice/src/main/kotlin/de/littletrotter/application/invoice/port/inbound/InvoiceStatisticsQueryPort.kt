package de.littletrotter.application.invoice.port.inbound

import de.littletrotter.application.dto.ArticleTotals
import de.littletrotter.application.dto.DailyTrend
import de.littletrotter.application.dto.MonthlyTotals
import de.littletrotter.application.dto.MonthlyTrend
import de.littletrotter.application.dto.QuarterlyTotals
import de.littletrotter.application.dto.RecipientTotals
import de.littletrotter.application.dto.SenderTotals
import de.littletrotter.application.dto.StatusDistribution
import de.littletrotter.application.dto.YearlyTotals
import de.littletrotter.application.dto.StatisticsFilter

/**
 * Input port of the "browse the invoice statistics" use case
 * ([de.littletrotter.ui.view.invoice.InvoiceStatisticsView] and its `KpiRow`/`RevenueMatrix`/
 * `TrendChart`/`StatusCardsRow` components): yearly/monthly/quarterly aggregates read from the
 * pre-aggregated `v_stat_*` views, top-N rankings, trend series, status distribution and the year
 * selector.
 *
 * Every method takes a [StatisticsFilter]; not every field is honored by every method — see the
 * per-method KDoc.
 */
interface InvoiceStatisticsQueryPort {
    /** Honors: senderId, recipientId, status, reverseCharge, filterText. */
    fun getYearlyTotals(filter: StatisticsFilter = StatisticsFilter.EMPTY): List<YearlyTotals>

    /** Honors: senderId, recipientId, status, reverseCharge, filterText. */
    fun getMonthlyTotals(year: Int, filter: StatisticsFilter = StatisticsFilter.EMPTY): List<MonthlyTotals>

    /** Honors: senderId, recipientId, status, reverseCharge, filterText. */
    fun getQuarterlyTotals(year: Int, filter: StatisticsFilter = StatisticsFilter.EMPTY): List<QuarterlyTotals>

    /** Honors: senderId, year, status, reverseCharge, filterText, month, quarter. */
    fun getRecipientTotals(limit: Int = 10, filter: StatisticsFilter = StatisticsFilter.EMPTY): List<RecipientTotals>

    /** Honors: recipientId, year, status, reverseCharge, filterText, month, quarter. */
    fun getSenderTotals(limit: Int = 10, filter: StatisticsFilter = StatisticsFilter.EMPTY): List<SenderTotals>

    /** Honors: senderId, recipientId, year, status, reverseCharge, filterText, month, quarter. */
    fun getArticleTotals(limit: Int = 10, filter: StatisticsFilter = StatisticsFilter.EMPTY): List<ArticleTotals>

    /** Honors: senderId, recipientId, status, reverseCharge, filterText. */
    fun getMonthlyTrend(year: Int, filter: StatisticsFilter = StatisticsFilter.EMPTY): List<MonthlyTrend>

    /** Honors: senderId, recipientId, status, reverseCharge, filterText. */
    fun getDailyTrend(year: Int, month: Int, filter: StatisticsFilter = StatisticsFilter.EMPTY): List<DailyTrend>

    /** Honors: senderId, recipientId, year, reverseCharge, filterText, month, quarter. */
    fun getStatusDistribution(filter: StatisticsFilter = StatisticsFilter.EMPTY): List<StatusDistribution>

    /** Honors: senderId, recipientId. */
    fun getAvailableYears(filter: StatisticsFilter = StatisticsFilter.EMPTY): List<Int>
}
