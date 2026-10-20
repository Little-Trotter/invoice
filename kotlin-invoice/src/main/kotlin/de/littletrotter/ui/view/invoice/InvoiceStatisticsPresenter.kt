package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.icon.VaadinIcon
import de.littletrotter.application.dto.DailyTrend
import de.littletrotter.application.dto.MonthlyTrend
import de.littletrotter.application.dto.QuarterlyTotals
import de.littletrotter.application.dto.StatisticsFilter
import de.littletrotter.application.invoice.port.inbound.InvoiceStatisticsQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import de.littletrotter.ui.component.statistics.DrillTarget
import de.littletrotter.ui.component.statistics.KpiAccent
import de.littletrotter.ui.component.statistics.KpiTone
import de.littletrotter.ui.component.statistics.KpiDelta
import de.littletrotter.ui.component.statistics.KpiTile
import de.littletrotter.ui.component.statistics.LabeledValue
import de.littletrotter.ui.component.statistics.PeriodCard
import de.littletrotter.ui.component.statistics.PeriodCardGroup
import de.littletrotter.ui.component.statistics.StatisticsPeriod
import de.littletrotter.ui.component.statistics.StatusCard
import de.littletrotter.ui.component.statistics.StatusColorFamily
import de.littletrotter.ui.component.statistics.TrendModel
import de.littletrotter.ui.component.statistics.TrendPoint
import de.littletrotter.ui.component.statistics.TrendSeries
import de.littletrotter.ui.format.MoneyFormatter
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.invoice.TrendChartSupport.fillMissingDays
import de.littletrotter.ui.view.invoice.TrendChartSupport.fillMissingMonths
import de.littletrotter.ui.view.invoice.TrendChartSupport.quarterLabels
import de.littletrotter.ui.view.invoice.TrendChartSupport.quarterOf
import de.littletrotter.ui.view.invoice.TrendChartSupport.shortMonth
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/**
 * Turns invoice statistics queries into the presentation models of the shared statistics
 * components. Every amount carries its own invoice's currency, so each section is grouped by
 * currency: the common all-EUR case yields one unlabeled group, a period mixing currencies yields
 * one group per currency. The year-over-year badges stay a single currency-blind percentage
 * comparison, since a percentage carries no currency of its own.
 *
 * All methods honor the senderId/recipientId/status/reverseCharge/filterText fields of the given
 * [StatisticsFilter]; its `month`/`quarter` (already gated to the active period by the view)
 * select a single sub-period where the section supports it.
 */
class InvoiceStatisticsPresenter(
    private val statisticsQuery: InvoiceStatisticsQueryPort,
    private val host: Component? = null,
) {

    private fun tr(key: String, vararg params: Any): String = UiTranslations.tr(host, key, *params)

    // ── KPI tiles ───────────────────────────────────────────────────────────────────────────

    /** Gross/net revenue (each with a year-over-year badge), invoice count and average value. */
    fun kpiTiles(filter: StatisticsFilter, period: StatisticsPeriod, year: Int): List<KpiTile> {
        val result = when (period) {
            StatisticsPeriod.YEAR -> yearResult(filter, year)
            StatisticsPeriod.MONTH -> monthResult(filter, year, filter.month)
            StatisticsPeriod.QUARTER -> quarterResult(filter, year, filter.quarter)
        }
        val groups = result.groups.sortedBy { it.currency.ordinal }
        val previous = previousPeriod(filter, period, year)
        val priorYear = year - 1

        val (grossValue, grossExtra) = moneyLines(groups.map { it.currency to it.gross })
        val (netValue, netExtra) = moneyLines(groups.map { it.currency to it.net })
        val (avgValue, avgExtra) = moneyLines(groups.map { it.currency to it.avg })
        return listOf(
            KpiTile(tr("statistics.kpi.gross"), grossValue, KpiAccent(VaadinIcon.MONEY, KpiTone.PRIMARY), grossExtra, delta(result.gross, previous.gross, priorYear)),
            KpiTile(tr("statistics.kpi.net"), netValue, KpiAccent(VaadinIcon.CHART, KpiTone.SUCCESS), netExtra, delta(result.net, previous.net, priorYear)),
            KpiTile(tr("statistics.kpi.count"), result.count.toString(), KpiAccent(VaadinIcon.INVOICE, KpiTone.MUTED)),
            KpiTile(tr("statistics.kpi.average"), avgValue, KpiAccent(VaadinIcon.CALC, KpiTone.ERROR), avgExtra),
        )
    }

    /** One row per currency present in the period; `avg` is that one currency's own gross/count. */
    private data class CurrencyTotals(val currency: Currency, val net: Money, val gross: Money, val count: Long, val avg: Money)

    /** Scalar net/gross/count (currency-blind, used by the count card and YoY) plus the groups. */
    private data class PeriodResult(val net: Money, val gross: Money, val count: Long, val groups: List<CurrencyTotals>)

    /** Gross/net of one prior period for the year-over-year comparison. */
    private data class PreviousAmounts(val gross: Money, val net: Money)

    /** One row's worth of (currency, net, gross, count), the common shape month/quarter rows fold to. */
    private data class CurrencyRow(val currency: Currency, val net: Money, val gross: Money, val count: Long)

    private fun yearResult(filter: StatisticsFilter, year: Int): PeriodResult {
        val rows = statisticsQuery.getYearlyTotals(filter).filter { it.year == year }
        val groups = rows.map { CurrencyTotals(it.currency, it.netAmount, it.grossAmount, it.invoiceCount, it.avgInvoiceAmount) }
        return PeriodResult(sum(rows.map { it.netAmount }), sum(rows.map { it.grossAmount }), rows.sumOf { it.invoiceCount }, groups)
    }

    private fun monthResult(filter: StatisticsFilter, year: Int, month: Int?): PeriodResult {
        val monthly = statisticsQuery.getMonthlyTotals(year, filter)
        val rows = if (month != null) monthly.filter { it.month == month } else monthly
        return foldRows(rows.map { CurrencyRow(it.currency, it.netAmount, it.grossAmount, it.invoiceCount) })
    }

    private fun quarterResult(filter: StatisticsFilter, year: Int, quarter: Int?): PeriodResult {
        val quarterly = statisticsQuery.getQuarterlyTotals(year, filter)
        val rows = if (quarter != null) quarterly.filter { it.quarter == quarter } else quarterly
        return foldRows(rows.map { CurrencyRow(it.currency, it.netAmount, it.grossAmount, it.invoiceCount) })
    }

    private fun foldRows(rows: List<CurrencyRow>): PeriodResult {
        val groups = rows.groupBy { it.currency }.map { (currency, groupRows) ->
            val gross = sum(groupRows.map { it.gross })
            CurrencyTotals(currency, sum(groupRows.map { it.net }), gross, groupRows.sumOf { it.count }, averageOf(gross, groupRows.sumOf { it.count }))
        }
        return PeriodResult(sum(rows.map { it.net }), sum(rows.map { it.gross }), rows.sumOf { it.count }, groups)
    }

    private fun sum(amounts: List<Money>): Money = amounts.fold(Money.ZERO) { acc, x -> acc + x }

    /** Gross/count rounded to 2 dp (HALF_UP); a zero count means "no data", not a division by zero. */
    private fun averageOf(gross: Money, count: Long): Money =
        if (count > 0) Money.of(gross.amount.divide(BigDecimal(count), 2, RoundingMode.HALF_UP)) else Money.ZERO

    /** Same sub-period, previous year (apples to apples); a missing prior row counts as zero. */
    private fun previousPeriod(filter: StatisticsFilter, period: StatisticsPeriod, year: Int): PreviousAmounts {
        val month = filter.month
        val quarter = filter.quarter
        return when (period) {
            StatisticsPeriod.MONTH ->
                if (month == null) {
                    previousYear(filter, year)
                } else {
                    val prev = statisticsQuery.getMonthlyTotals(year - 1, filter).find { it.month == month }
                    PreviousAmounts(prev?.grossAmount ?: Money.ZERO, prev?.netAmount ?: Money.ZERO)
                }
            StatisticsPeriod.QUARTER ->
                if (quarter == null) {
                    previousYear(filter, year)
                } else {
                    val prev = statisticsQuery.getQuarterlyTotals(year - 1, filter).find { it.quarter == quarter }
                    PreviousAmounts(prev?.grossAmount ?: Money.ZERO, prev?.netAmount ?: Money.ZERO)
                }
            StatisticsPeriod.YEAR -> previousYear(filter, year)
        }
    }

    private fun previousYear(filter: StatisticsFilter, year: Int): PreviousAmounts {
        val prev = statisticsQuery.getYearlyTotals(filter).find { it.year == year - 1 }
        return PreviousAmounts(prev?.grossAmount ?: Money.ZERO, prev?.netAmount ?: Money.ZERO)
    }

    /** Headline value plus one extra line per further currency; no data at all reads as EUR zero. */
    private fun moneyLines(groups: List<Pair<Currency, Money>>): Pair<String, List<String>> {
        if (groups.isEmpty()) return MoneyFormatter.format(Money.ZERO, Currency.EUR) to emptyList()
        val lines = groups.map { (currency, amount) -> MoneyFormatter.format(amount, currency) }
        return lines.first() to lines.drop(1)
    }

    private fun delta(current: Money, previous: Money, priorYear: Int): KpiDelta? {
        val result = computeYoy(current, previous) ?: return null
        val text = "${if (result.up) "↑" else "↓"} ${String.format(Locale.GERMANY, "%+.1f%%", result.pctChange)} ${tr("statistics.delta.vsYear", priorYear.toString())}"
        return KpiDelta(text, result.up)
    }

    // ── Revenue matrix ──────────────────────────────────────────────────────────────────────

    /** One card row per currency: Q1..Q4 plus the total card; no data still yields one EUR row. */
    fun quarterCards(filter: StatisticsFilter, year: Int): List<PeriodCardGroup> {
        val byCurrency = statisticsQuery.getQuarterlyTotals(year, filter).groupBy { it.currency }
            .ifEmpty { mapOf(Currency.EUR to emptyList()) }
        val currencies = byCurrency.keys.sortedBy { it.ordinal }
        return currencies.map { currency ->
            PeriodCardGroup(
                heading = if (currencies.size > 1) currency.code else null,
                cards = quarterRow(byCurrency.getValue(currency), currency),
            )
        }
    }

    /** The drill key of the highlighted card: the selected quarter, only in quarter mode. */
    fun activeQuarter(filter: StatisticsFilter, period: StatisticsPeriod): Int? =
        if (period == StatisticsPeriod.QUARTER) filter.quarter else null

    private fun quarterRow(rows: List<QuarterlyTotals>, currency: Currency): List<PeriodCard> {
        val byQ = (1..QUARTERS_PER_YEAR).map { q -> rows.find { it.quarter == q } }
        val maxGross = byQ.fold(Money.ZERO) { acc, x -> maxOf(acc, x?.grossAmount ?: Money.ZERO) }
        val quarters = byQ.mapIndexed { i, data ->
            val gross = data?.grossAmount ?: Money.ZERO
            PeriodCard(
                label = "Q${i + 1}",
                rows = revenueRows(RevenueAmounts(data?.netAmount ?: Money.ZERO, gross, data?.invoiceCount ?: 0L), currency, emphasised = false),
                barShare = barPercent(gross, maxGross),
                drillKey = i + 1,
            )
        }
        val total = PeriodCard(
            label = tr("statistics.card.total"),
            rows = revenueRows(
                RevenueAmounts(
                    sum(byQ.map { it?.netAmount ?: Money.ZERO }), sum(byQ.map { it?.grossAmount ?: Money.ZERO }),
                    byQ.sumOf { it?.invoiceCount ?: 0L },
                ),
                currency, emphasised = true,
            ),
            primary = true,
        )
        return quarters + total
    }

    /** Net/gross/count of one card, bundled so [revenueRows] stays within the parameter limit. */
    private data class RevenueAmounts(val net: Money, val gross: Money, val count: Long)

    private fun revenueRows(amounts: RevenueAmounts, currency: Currency, emphasised: Boolean): List<LabeledValue> = listOf(
        LabeledValue(tr("statistics.card.net"), MoneyFormatter.format(amounts.net, currency), bold = emphasised),
        LabeledValue(tr("statistics.card.gross"), MoneyFormatter.format(amounts.gross, currency), bold = true),
        LabeledValue(tr("statistics.card.count"), amounts.count.toString(), bold = emphasised),
    )

    /** Share of [maxGross] as whole percent; the all-zero case is a genuine 0 %, never a division. */
    private fun barPercent(gross: Money, maxGross: Money): Int =
        if (maxGross <= Money.ZERO) {
            0
        } else {
            gross.amount.divide(maxGross.amount, DIVISION_SCALE, RoundingMode.HALF_UP)
                .multiply(BigDecimal(PERCENT_SCALE)).toInt().coerceIn(0, PERCENT_SCALE)
        }

    // ── Status cards ────────────────────────────────────────────────────────────────────────

    /**
     * Six cards (draft, in review, sent, cancelled, voided, total) for [year]. The filter's own
     * status is ignored by the query: this breakdown is by status.
     */
    fun statusCards(filter: StatisticsFilter, year: Int): List<StatusCard> {
        val distribution = statisticsQuery.getStatusDistribution(filter.copy(year = year))
        val total = distribution.sumOf { it.count }
        fun countOf(status: InvoiceStatus): Long = distribution.find { it.status == status }?.count ?: 0
        fun card(label: String, count: Long, colors: StatusColorFamily, icon: VaadinIcon) =
            StatusCard(label, count, pctOf(count, total), colors, icon)
        return listOf(
            card(tr("statistics.status.draft"), countOf(InvoiceStatus.DRAFT), StatusColorFamily.NEUTRAL, VaadinIcon.FILE_TEXT),
            card(tr("statistics.status.review"), countOf(InvoiceStatus.IN_REVIEW), StatusColorFamily.PRIMARY, VaadinIcon.EYE),
            card(tr(InvoiceStatus.ISSUED.labelKey), countOf(InvoiceStatus.ISSUED), StatusColorFamily.SUCCESS, VaadinIcon.CHECK_CIRCLE),
            // A cancelled (cancellation) invoice was really sent, a voided one never was, so the two
            // terminal states keep different colour families.
            card(tr("statistics.status.cancelled"), countOf(InvoiceStatus.CANCELLED), StatusColorFamily.CANCELLATION, VaadinIcon.FILE_REMOVE),
            card(tr("statistics.status.voided"), countOf(InvoiceStatus.VOIDED), StatusColorFamily.ERROR, VaadinIcon.CLOSE_CIRCLE),
            StatusCard(tr("statistics.status.total"), total, if (total > 0) "100%" else "—", StatusColorFamily.TOTAL, VaadinIcon.LIST),
        )
    }

    /** The "Angezeigt für: …" caption under the status cards. */
    fun statusContext(filter: StatisticsFilter, period: StatisticsPeriod, year: Int): String =
        buildContextLabel(period, filter.month, filter.quarter, year, host)

    // ── Trend chart ─────────────────────────────────────────────────────────────────────────

    /** Net/gross trend for the active period: year → quarters, quarter → months, month → days. */
    fun trend(filter: StatisticsFilter, year: Int, period: StatisticsPeriod, onDrill: (DrillTarget) -> Unit): TrendModel {
        val month = filter.month
        val quarter = filter.quarter
        // An empty result still yields exactly one (empty) EUR group so the zero-filled fallback
        // chart keeps rendering.
        val pointsByCurrency: Map<Currency, List<TrendPoint>> = when (period) {
            StatisticsPeriod.YEAR -> yearTrend(year, filter)
            StatisticsPeriod.QUARTER ->
                if (quarter == null) {
                    yearTrend(year, filter)
                } else {
                    byCurrency(statisticsQuery.getMonthlyTrend(year, filter), { it.currency }) { quarterMonthPoints(it, quarter, year) }
                }
            StatisticsPeriod.MONTH ->
                if (month != null) {
                    byCurrency(statisticsQuery.getDailyTrend(year, month, filter), { it.currency }) { dayPoints(it, year, month) }
                } else {
                    byCurrency(statisticsQuery.getMonthlyTrend(year, filter), { it.currency }) { monthPoints(it, year) }
                }
        }
        val series = pointsByCurrency.map { (currency, points) -> TrendSeries(currency, points) }
        return TrendModel(trendTitle(period, quarter, month, year), series, onDrill)
    }

    private fun yearTrend(year: Int, filter: StatisticsFilter): Map<Currency, List<TrendPoint>> =
        byCurrency(statisticsQuery.getQuarterlyTotals(year, filter), { it.currency }) { yearPoints(it) }

    private fun <T> byCurrency(rows: List<T>, currencyOf: (T) -> Currency, points: (List<T>) -> List<TrendPoint>): Map<Currency, List<TrendPoint>> =
        rows.groupBy(currencyOf).ifEmpty { mapOf(Currency.EUR to emptyList()) }.mapValues { (_, group) -> points(group) }

    private fun yearPoints(quarterlyForOneCurrency: List<QuarterlyTotals>): List<TrendPoint> =
        (1..QUARTERS_PER_YEAR).map { q ->
            val match = quarterlyForOneCurrency.find { it.quarter == q }
            TrendPoint(
                quarterLabels.getValue(q).substringBefore(" "), match?.netAmount ?: Money.ZERO, match?.grossAmount ?: Money.ZERO,
                DrillTarget(StatisticsPeriod.QUARTER, q),
            )
        }

    private fun quarterMonthPoints(monthlyForOneCurrency: List<MonthlyTrend>, quarter: Int, year: Int): List<TrendPoint> {
        val filtered = monthlyForOneCurrency.filter { quarterOf(it.month) == quarter }
        val start = (quarter - 1) * MONTHS_PER_QUARTER + 1
        return fillMissingMonths(filtered, start..<start + MONTHS_PER_QUARTER, year).sortedBy { it.month }.map {
            TrendPoint(Month.of(it.month).getDisplayName(TextStyle.FULL, Locale.GERMAN), it.netAmount, it.grossAmount, DrillTarget(StatisticsPeriod.MONTH, it.month))
        }
    }

    private fun monthPoints(monthlyForOneCurrency: List<MonthlyTrend>, year: Int): List<TrendPoint> =
        fillMissingMonths(monthlyForOneCurrency, 1..MONTHS_PER_YEAR, year).sortedBy { it.month }.map {
            TrendPoint(shortMonth(it.month), it.netAmount, it.grossAmount, DrillTarget(StatisticsPeriod.MONTH, it.month))
        }

    private fun dayPoints(dailyForOneCurrency: List<DailyTrend>, year: Int, month: Int): List<TrendPoint> =
        fillMissingDays(dailyForOneCurrency, year, month).sortedBy { it.day }.map { TrendPoint("${it.day}.", it.netAmount, it.grossAmount) }

    private fun trendTitle(period: StatisticsPeriod, quarter: Int?, month: Int?, year: Int): String = when (period) {
        StatisticsPeriod.YEAR -> tr("statistics.trend.title.quarters", year.toString())
        StatisticsPeriod.QUARTER ->
            if (quarter != null) {
                tr("statistics.trend.title.quarterMonths", quarterLabels[quarter] ?: "Q$quarter", year.toString())
            } else {
                tr("statistics.trend.title.quarters", year.toString())
            }
        StatisticsPeriod.MONTH ->
            if (month != null) {
                tr("statistics.trend.title.days", Month.of(month).getDisplayName(TextStyle.FULL, Locale.GERMAN), year.toString())
            } else {
                tr("statistics.trend.title.months", year.toString())
            }
    }

    /** Percent change of `current` vs. `previous`, and whether that change is an increase. */
    internal data class YoyResult(val pctChange: BigDecimal, val up: Boolean)

    companion object {
        private const val QUARTERS_PER_YEAR = 4
        private const val MONTHS_PER_QUARTER = 3
        private const val MONTHS_PER_YEAR = 12
        private const val DIVISION_SCALE = 4
        private const val PERCENT_SCALE = 100

        /**
         * Pure year-over-year calculation. Returns `null` when `previous` is zero or negative:
         * there is no meaningful percentage to show and the badge is hidden.
         */
        internal fun computeYoy(current: Money, previous: Money): YoyResult? {
            if (previous <= Money.ZERO) return null
            val pct = (current.amount - previous.amount)
                .divide(previous.amount, DIVISION_SCALE, RoundingMode.HALF_UP)
                .multiply(BigDecimal(PERCENT_SCALE))
            return YoyResult(pct, pct >= BigDecimal.ZERO)
        }

        /** `n` as a percentage of `total`, one decimal place; `"—"` when `total` is zero. */
        internal fun pctOf(n: Long, total: Long): String =
            if (total > 0) String.format(Locale.GERMANY, "%.1f%%", n.toDouble() / total * 100) else "—"

        internal fun buildContextLabel(period: StatisticsPeriod, month: Int?, quarter: Int?, year: Int, host: Component? = null): String {
            val wholeYear = UiTranslations.tr(host, "statistics.context.year", year.toString())
            val periodStr = when (period) {
                StatisticsPeriod.MONTH ->
                    if (month != null) "${Month.of(month).getDisplayName(TextStyle.FULL, Locale.GERMAN)} $year" else wholeYear
                StatisticsPeriod.QUARTER ->
                    if (quarter != null) "${quarterLabels[quarter] ?: "Q$quarter"} $year" else wholeYear
                StatisticsPeriod.YEAR -> wholeYear
            }
            return UiTranslations.tr(host, "statistics.context.shownFor", periodStr)
        }
    }
}
