package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.icon.VaadinIcon
import de.littletrotter.application.quote.port.inbound.QuoteDashboard
import de.littletrotter.application.quote.port.inbound.QuoteKpis
import de.littletrotter.application.quote.port.inbound.QuoteQuarterTotals
import de.littletrotter.application.quote.port.inbound.QuoteRankingRow
import de.littletrotter.application.quote.port.inbound.QuoteStatusCounts
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.application.quote.port.inbound.QuoteTrendPoint
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
import de.littletrotter.ui.view.invoice.InvoiceStatisticsPresenter
import de.littletrotter.ui.view.invoice.TrendChartSupport
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

private const val NO_VALUE = "–"
private const val QUARTERS_PER_YEAR = 4
private const val MONTHS_PER_QUARTER = 3
private const val MONTHS_PER_YEAR = 12
private const val PERCENT = 100
private const val VS_PREVIOUS_YEAR = "delta.vsPreviousYear"
private const val BAR_SCALE = 4
private const val DAYS_FRACTION_DIGITS = 1

/**
 * Turns an offer [QuoteDashboard] into the presentation models of the shared statistics
 * components, the same sections the invoice statistics show. Only sent offers carry a volume, so
 * the trend draws net bars alone. A page of offers has a single currency, hence no currency
 * grouping. Every text comes from the message bundle of [host].
 */
class QuoteStatisticsPresenter(private val host: Component? = null) {

    private fun tr(key: String, vararg params: Any): String = UiTranslations.tr(host, "quote.statistics.$key", *params)

    // ── KPI tiles ───────────────────────────────────────────────────────────────────────────

    /** Sent volume, sent count and average value against the previous year, work in progress, days to sending. */
    fun kpiTiles(dashboard: QuoteDashboard): List<KpiTile> {
        val now = dashboard.kpis
        val before = dashboard.kpisPreviousYear
        return listOf(
            KpiTile(
                tr("kpi.sentNet"), MoneyFormatter.format(now.sentVolume),
                KpiAccent(VaadinIcon.PAPERPLANE, KpiTone.PRIMARY),
                delta = percentDelta(now.sentVolume, before.sentVolume),
            ),
            KpiTile(
                tr("kpi.sentCount"), now.sentCount.toString(),
                KpiAccent(VaadinIcon.FILE_TEXT, KpiTone.SUCCESS),
                delta = countDelta(now, before),
            ),
            KpiTile(
                tr("kpi.average"), now.averageSent?.let { MoneyFormatter.format(it) } ?: NO_VALUE,
                KpiAccent(VaadinIcon.CALC, KpiTone.MUTED),
                delta = averageDelta(now, before),
            ),
            KpiTile(
                tr("kpi.inProgress"), MoneyFormatter.format(now.inProgressAmount),
                KpiAccent(VaadinIcon.PENCIL, KpiTone.ERROR),
                caption = tr("kpi.inProgress.caption", now.inProgressCount),
            ),
            KpiTile(
                tr("kpi.avgDays"), now.avgDaysToSend?.let(::days) ?: NO_VALUE,
                KpiAccent(VaadinIcon.CLOCK, KpiTone.PRIMARY),
                delta = daysDelta(now.avgDaysToSend, before.avgDaysToSend),
            ),
        )
    }

    private fun percentDelta(current: Money, previous: Money): KpiDelta? {
        val change = InvoiceStatisticsPresenter.computeYoy(current, previous) ?: return null
        val text = "${arrow(change.up)} ${String.format(Locale.GERMANY, "%+.1f%%", change.pctChange)} ${tr(VS_PREVIOUS_YEAR)}"
        return KpiDelta(text, change.up)
    }

    private fun countDelta(current: QuoteKpis, previous: QuoteKpis): KpiDelta? {
        if (current.sentCount == 0 && previous.sentCount == 0) return null
        val difference = current.sentCount - previous.sentCount
        return KpiDelta("${arrow(difference >= 0)} ${String.format(Locale.GERMANY, "%+d", difference)} ${tr(VS_PREVIOUS_YEAR)}", difference >= 0)
    }

    private fun averageDelta(current: QuoteKpis, previous: QuoteKpis): KpiDelta? {
        val now = current.averageSent ?: return null
        val before = previous.averageSent ?: return null
        return percentDelta(now, before)
    }

    /** Fewer days to sending is the better direction, so a decrease counts as positive. */
    private fun daysDelta(current: BigDecimal?, previous: BigDecimal?): KpiDelta? {
        if (current == null || previous == null) return null
        val difference = current - previous
        val shorter = difference.signum() <= 0
        return KpiDelta("${arrow(!shorter)} ${String.format(Locale.GERMANY, "%+.1f", difference)} ${tr(VS_PREVIOUS_YEAR)}", shorter)
    }

    private fun arrow(up: Boolean): String = if (up) "↑" else "↓"

    private fun days(value: BigDecimal): String =
        NumberFormat.getNumberInstance(Locale.GERMANY).apply { maximumFractionDigits = DAYS_FRACTION_DIGITS }.format(value)

    // ── Volume by quarter ───────────────────────────────────────────────────────────────────

    /** One card row: Q1..Q4 plus the total card; a quarter without offers reads zero. */
    fun quarterCards(dashboard: QuoteDashboard): List<PeriodCardGroup> {
        val byQuarter = (1..QUARTERS_PER_YEAR).map { q ->
            dashboard.quarters.find { it.quarter == q } ?: QuoteQuarterTotals(q, Money.ZERO, 0)
        }
        val maxNet = byQuarter.fold(Money.ZERO) { acc, quarter -> maxOf(acc, quarter.net) }
        val quarters = byQuarter.map { quarter ->
            PeriodCard(
                label = "Q${quarter.quarter}",
                rows = amountRows(quarter.net, quarter.count, emphasised = false),
                barShare = barPercent(quarter.net, maxNet),
                drillKey = quarter.quarter,
            )
        }
        val total = PeriodCard(
            label = tr("card.total"),
            rows = amountRows(Money.sum(byQuarter.map { it.net }), byQuarter.sumOf { it.count }, emphasised = true),
            primary = true,
        )
        return listOf(PeriodCardGroup(heading = null, cards = quarters + total))
    }

    /** The drill key of the highlighted card: the selected quarter, only in quarter mode. */
    fun activeQuarter(period: StatisticsPeriod, quarter: Int?): Int? = if (period == StatisticsPeriod.QUARTER) quarter else null

    private fun amountRows(net: Money, count: Int, emphasised: Boolean): List<LabeledValue> = listOf(
        LabeledValue(tr("card.net"), MoneyFormatter.format(net), bold = true),
        LabeledValue(tr("card.count"), count.toString(), bold = emphasised),
    )

    /** Share of [maxNet] as whole percent; the all-zero case is a genuine 0 %, never a division. */
    private fun barPercent(net: Money, maxNet: Money): Int =
        if (maxNet <= Money.ZERO) {
            0
        } else {
            net.amount.divide(maxNet.amount, BAR_SCALE, RoundingMode.HALF_UP).multiply(BigDecimal(PERCENT)).toInt().coerceIn(0, PERCENT)
        }

    // ── Status cards ────────────────────────────────────────────────────────────────────────

    /** Draft, review, sent and cancelled with their share of the total, plus the total itself. */
    fun statusCards(counts: QuoteStatusCounts): List<StatusCard> {
        val total = counts.total.toLong()
        fun card(key: String, count: Int, colors: StatusColorFamily, icon: VaadinIcon) =
            StatusCard(tr(key), count.toLong(), InvoiceStatisticsPresenter.pctOf(count.toLong(), total), colors, icon)
        return listOf(
            card("status.draft", counts.draft, StatusColorFamily.NEUTRAL, QuoteLabels.statusGlyph(QuoteStatus.DRAFT)),
            card("status.review", counts.review, StatusColorFamily.PRIMARY, QuoteLabels.statusGlyph(QuoteStatus.REVIEW)),
            card("status.sent", counts.sent, StatusColorFamily.SUCCESS, QuoteLabels.statusGlyph(QuoteStatus.SENT)),
            card("status.cancelled", counts.cancelled, StatusColorFamily.CANCELLATION, QuoteLabels.statusGlyph(QuoteStatus.CANCELLED)),
            StatusCard(tr("status.total"), total, if (total > 0) "100%" else "—", StatusColorFamily.TOTAL, VaadinIcon.LIST),
        )
    }

    /** The "Angezeigt für: …" caption under the status cards. */
    fun statusContext(period: StatisticsPeriod, month: Int?, quarter: Int?, year: Int): String {
        val wholeYear = tr("context.year", year.toString())
        val shown = when (period) {
            StatisticsPeriod.MONTH -> if (month != null) "${monthName(month)} $year" else wholeYear
            StatisticsPeriod.QUARTER -> if (quarter != null) "${tr("quarter.$quarter")} $year" else wholeYear
            StatisticsPeriod.YEAR -> wholeYear
        }
        return tr("context.shownFor", shown)
    }

    // ── Trend ───────────────────────────────────────────────────────────────────────────────

    /** Sent net for the active period: year → quarters, quarter → months, month → days. */
    fun trend(
        dashboard: QuoteDashboard,
        period: StatisticsPeriod,
        selection: PeriodSelection,
        onDrill: (DrillTarget) -> Unit,
    ): TrendModel {
        val (year, quarter, month) = selection
        val points = when (period) {
            StatisticsPeriod.QUARTER ->
                if (quarter != null) quarterMonthPoints(dashboard.trend, year, quarter) else quarterPoints(dashboard.quarters)
            StatisticsPeriod.MONTH ->
                if (month != null) dayPoints(dashboard.trend, year, month) else monthPoints(dashboard.trend, year)
            StatisticsPeriod.YEAR -> quarterPoints(dashboard.quarters)
        }
        return TrendModel(trendTitle(period, selection), listOf(TrendSeries(Currency.EUR, points)), onDrill, showGross = false)
    }

    /** The selected span as the page holds it: [quarter] and [month] are only set in their own mode. */
    data class PeriodSelection(val year: Int, val quarter: Int?, val month: Int?)

    private fun quarterPoints(quarters: List<QuoteQuarterTotals>): List<TrendPoint> =
        (1..QUARTERS_PER_YEAR).map { q ->
            TrendPoint("Q$q", quarters.find { it.quarter == q }?.net ?: Money.ZERO, Money.ZERO, DrillTarget(StatisticsPeriod.QUARTER, q))
        }

    private fun quarterMonthPoints(trend: List<QuoteTrendPoint>, year: Int, quarter: Int): List<TrendPoint> {
        val first = (quarter - 1) * MONTHS_PER_QUARTER + 1
        return (first..<first + MONTHS_PER_QUARTER).map { m ->
            TrendPoint(monthName(m), monthNet(trend, year, m), Money.ZERO, DrillTarget(StatisticsPeriod.MONTH, m))
        }
    }

    private fun monthPoints(trend: List<QuoteTrendPoint>, year: Int): List<TrendPoint> =
        (1..MONTHS_PER_YEAR).map { m ->
            TrendPoint(TrendChartSupport.shortMonth(m), monthNet(trend, year, m), Money.ZERO, DrillTarget(StatisticsPeriod.MONTH, m))
        }

    private fun monthNet(trend: List<QuoteTrendPoint>, year: Int, month: Int): Money =
        trend.find { it.day.year == year && it.day.monthValue == month }?.net ?: Money.ZERO

    private fun dayPoints(trend: List<QuoteTrendPoint>, year: Int, month: Int): List<TrendPoint> =
        (1..LocalDate.of(year, month, 1).lengthOfMonth()).map { d ->
            TrendPoint("$d.", trend.find { it.day == LocalDate.of(year, month, d) }?.net ?: Money.ZERO, Money.ZERO)
        }

    private fun trendTitle(period: StatisticsPeriod, selection: PeriodSelection): String {
        val year = selection.year.toString()
        val quarter = selection.quarter
        val month = selection.month
        return when (period) {
            StatisticsPeriod.YEAR -> tr("trend.title.quarters", year)
            StatisticsPeriod.QUARTER ->
                if (quarter != null) tr("trend.title.quarterMonths", tr("quarter.$quarter"), year) else tr("trend.title.quarters", year)
            StatisticsPeriod.MONTH ->
                if (month != null) tr("trend.title.days", monthName(month), year) else tr("trend.title.months", year)
        }
    }

    private fun monthName(month: Int): String = Month.of(month).getDisplayName(TextStyle.FULL, Locale.GERMAN)

    // ── Rankings ────────────────────────────────────────────────────────────────────────────

    /** Customer line: name, sent net, number of offers and the share of the listed net. */
    fun customerRow(row: QuoteRankingRow, all: List<QuoteRankingRow>): List<String> {
        val total = all.fold(Money.ZERO) { acc, other -> acc + other.net }
        val share = if (total > Money.ZERO) {
            row.net.amount.divide(total.amount, SHARE_SCALE, RoundingMode.HALF_UP).multiply(BigDecimal(PERCENT))
        } else {
            BigDecimal.ZERO
        }
        return listOf(row.name, MoneyFormatter.format(row.net), row.count.toString(), String.format(Locale.GERMANY, "%.1f%%", share))
    }

    /** Position line: name, sent net and number of offers. */
    fun positionRow(row: QuoteRankingRow): List<String> =
        listOf(row.name, MoneyFormatter.format(row.net), row.count.toString())

    private companion object {
        const val SHARE_SCALE = 4
    }
}
