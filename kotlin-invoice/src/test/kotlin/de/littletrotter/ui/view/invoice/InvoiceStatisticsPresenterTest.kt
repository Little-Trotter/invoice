package de.littletrotter.ui.view.invoice

import de.littletrotter.application.dto.DailyTrend
import de.littletrotter.application.dto.MonthlyTotals
import de.littletrotter.application.dto.MonthlyTrend
import de.littletrotter.application.dto.QuarterlyTotals
import de.littletrotter.application.dto.StatisticsFilter
import de.littletrotter.application.dto.StatusDistribution
import de.littletrotter.application.dto.YearlyTotals
import de.littletrotter.application.invoice.port.inbound.InvoiceStatisticsQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import de.littletrotter.ui.component.statistics.DrillTarget
import de.littletrotter.ui.component.statistics.StatisticsPeriod
import de.littletrotter.ui.format.MoneyFormatter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.util.Locale

class InvoiceStatisticsPresenterTest {

    private val query: InvoiceStatisticsQueryPort = mock()
    private val presenter = InvoiceStatisticsPresenter(query)

    private fun money(v: String) = Money.of(BigDecimal(v))
    private fun eur(v: String) = MoneyFormatter.format(money(v), Currency.EUR)
    private fun chf(v: String) = MoneyFormatter.format(money(v), Currency.CHF)
    private fun year(y: Int, net: String, gross: String, count: Long, avg: String, c: Currency = Currency.EUR) =
        YearlyTotals(y, money(net), Money.ZERO, money(gross), count, money(avg), c)
    private fun month(y: Int, m: Int, net: String, gross: String, count: Long, c: Currency = Currency.EUR) =
        MonthlyTotals(y, m, money(net), Money.ZERO, money(gross), count, c)
    private fun quarter(y: Int, q: Int, net: String, gross: String, count: Long, c: Currency = Currency.EUR) =
        QuarterlyTotals(y, q, money(net), Money.ZERO, money(gross), count, c)

    // ── KPI tiles ───────────────────────────────────────────────────────────

    @Test
    fun `year without data renders zeros and no delta`() {
        whenever(query.getYearlyTotals(any())).thenReturn(emptyList())

        val tiles = presenter.kpiTiles(StatisticsFilter(), StatisticsPeriod.YEAR, 2026)

        assertEquals(listOf("Bruttoumsatz", "Nettoumsatz", "Rechnungen", "Ø Rechnungsbetrag"), tiles.map { it.label })
        assertEquals(eur("0"), tiles[0].value)
        assertEquals("0", tiles[2].value)
        assertEquals(eur("0"), tiles[3].value)
        assertNull(tiles[0].delta)
        assertNull(tiles[1].delta)
        assertTrue(tiles.all { it.extraValues.isEmpty() })
    }

    @Test
    fun `year with previous year missing has no delta, with previous year shows arrow and percent`() {
        whenever(query.getYearlyTotals(any())).thenReturn(listOf(year(2026, "1000", "1190", 5, "238")))
        assertNull(presenter.kpiTiles(StatisticsFilter(), StatisticsPeriod.YEAR, 2026)[0].delta)

        whenever(query.getYearlyTotals(any())).thenReturn(
            listOf(year(2024, "500", "595", 1, "595"), year(2026, "2000", "2380", 5, "476"), year(2025, "1000", "1190", 5, "238")),
        )
        val delta = presenter.kpiTiles(StatisticsFilter(), StatisticsPeriod.YEAR, 2026)[0].delta!!
        assertTrue(delta.positive)
        assertEquals("↑ " + String.format(Locale.GERMANY, "%+.1f%%", 100.0) + " vs. 2025", delta.text)
        verify(query, never()).getMonthlyTotals(any(), any())
    }

    @Test
    fun `year with two currencies shows one line per currency and a currency-blind count`() {
        whenever(query.getYearlyTotals(any())).thenReturn(
            listOf(year(2026, "500", "500", 2, "250", Currency.CHF), year(2026, "1000", "1190", 5, "238")),
        )

        val tiles = presenter.kpiTiles(StatisticsFilter(), StatisticsPeriod.YEAR, 2026)

        assertEquals(eur("1190"), tiles[0].value)
        assertEquals(listOf(chf("500")), tiles[0].extraValues)
        assertEquals(listOf(chf("500")), tiles[1].extraValues)
        assertEquals(listOf(chf("250")), tiles[3].extraValues)
        assertEquals("7", tiles[2].value)
    }

    @Test
    fun `month selection uses that month and its own prior-year month`() {
        val filter = StatisticsFilter(month = 3)
        whenever(query.getMonthlyTotals(2026, filter)).thenReturn(
            listOf(month(2026, 1, "100", "119", 1), month(2026, 3, "500", "595", 2)),
        )
        whenever(query.getMonthlyTotals(2025, filter)).thenReturn(
            listOf(month(2025, 1, "50", "59", 1), month(2025, 3, "800", "952", 3)),
        )

        val tiles = presenter.kpiTiles(filter, StatisticsPeriod.MONTH, 2026)

        assertEquals("2", tiles[2].value)
        assertEquals(eur("297.50"), tiles[3].value)
        assertFalse(tiles[0].delta!!.positive)
        assertTrue(tiles[0].delta!!.text.startsWith("↓"))
        verify(query, never()).getYearlyTotals(any())
    }

    @Test
    fun `month without selection sums every month per currency and compares against the prior year`() {
        val filter = StatisticsFilter()
        whenever(query.getMonthlyTotals(2026, filter)).thenReturn(
            listOf(month(2026, 1, "100", "119", 1), month(2026, 2, "200", "238", 2), month(2026, 1, "50", "50", 1, Currency.CHF)),
        )
        whenever(query.getYearlyTotals(any())).thenReturn(emptyList())

        val tiles = presenter.kpiTiles(filter, StatisticsPeriod.MONTH, 2026)

        assertEquals(eur("357"), tiles[0].value)
        assertEquals(listOf(chf("50")), tiles[0].extraValues)
        assertEquals("4", tiles[2].value)
        verify(query, never()).getMonthlyTotals(eq(2025), any())
        verify(query).getYearlyTotals(any())
    }

    @Test
    fun `month selection without a matching row falls back to zero`() {
        whenever(query.getMonthlyTotals(any(), any())).thenReturn(emptyList())

        val tiles = presenter.kpiTiles(StatisticsFilter(month = 3), StatisticsPeriod.MONTH, 2026)

        assertEquals("0", tiles[2].value)
        assertEquals(eur("0"), tiles[3].value)
        assertNull(tiles[0].delta)
    }

    @Test
    fun `quarter selection uses that quarter and hides the delta when the prior quarter is missing`() {
        val filter = StatisticsFilter(quarter = 2)
        whenever(query.getQuarterlyTotals(2026, filter)).thenReturn(
            listOf(quarter(2026, 1, "100", "119", 1), quarter(2026, 2, "500", "595", 2)),
        )
        whenever(query.getQuarterlyTotals(2025, filter)).thenReturn(emptyList())

        val tiles = presenter.kpiTiles(filter, StatisticsPeriod.QUARTER, 2026)

        assertEquals("2", tiles[2].value)
        assertNull(tiles[0].delta)
    }

    @Test
    fun `quarter selection with a prior quarter shows a delta`() {
        val filter = StatisticsFilter(quarter = 2)
        whenever(query.getQuarterlyTotals(2026, filter)).thenReturn(listOf(quarter(2026, 2, "500", "595", 2)))
        whenever(query.getQuarterlyTotals(2025, filter)).thenReturn(
            listOf(quarter(2025, 1, "50", "59", 1), quarter(2025, 2, "200", "238", 2)),
        )

        assertTrue(presenter.kpiTiles(filter, StatisticsPeriod.QUARTER, 2026)[0].delta!!.positive)
    }

    @Test
    fun `quarter without selection and without data falls back to the year comparison`() {
        whenever(query.getQuarterlyTotals(any(), any())).thenReturn(emptyList())
        whenever(query.getYearlyTotals(any())).thenReturn(emptyList())

        val tiles = presenter.kpiTiles(StatisticsFilter(), StatisticsPeriod.QUARTER, 2026)

        assertEquals("0", tiles[2].value)
        verify(query).getYearlyTotals(any())
    }

    @Test
    fun `computeYoy covers zero, negative, increase, decrease and unchanged`() {
        assertNull(InvoiceStatisticsPresenter.computeYoy(money("100"), Money.ZERO))
        assertNull(InvoiceStatisticsPresenter.computeYoy(money("100"), money("-5")))
        assertEquals(0, BigDecimal("50.0000").compareTo(InvoiceStatisticsPresenter.computeYoy(money("150"), money("100"))!!.pctChange))
        assertFalse(InvoiceStatisticsPresenter.computeYoy(money("80"), money("100"))!!.up)
        assertTrue(InvoiceStatisticsPresenter.computeYoy(money("100"), money("100"))!!.up)
    }

    // ── Revenue cards ───────────────────────────────────────────────────────

    @Test
    fun `quarter cards without data yield one unlabeled all-zero EUR group`() {
        whenever(query.getQuarterlyTotals(any(), any())).thenReturn(emptyList())

        val groups = presenter.quarterCards(StatisticsFilter(), 2026)

        val group = groups.single()
        assertNull(group.heading)
        assertEquals(listOf("Q1", "Q2", "Q3", "Q4", "Gesamt"), group.cards.map { it.label })
        assertEquals(listOf(1, 2, 3, 4, null), group.cards.map { it.drillKey })
        assertTrue(group.cards.take(4).all { it.barShare == 0 })
        assertNull(group.cards.last().barShare)
        assertTrue(group.cards.last().primary)
        assertEquals(eur("0"), group.cards[0].rows[1].value)
    }

    @Test
    fun `quarter cards compute bar shares against the strongest quarter and total row`() {
        whenever(query.getQuarterlyTotals(any(), any())).thenReturn(
            listOf(quarter(2026, 1, "100", "200", 1), quarter(2026, 3, "50", "100", 2)),
        )

        val cards = presenter.quarterCards(StatisticsFilter(), 2026).single().cards

        assertEquals(listOf(100, 0, 50, 0), cards.take(4).map { it.barShare })
        val total = cards.last()
        assertEquals(eur("150"), total.rows[0].value)
        assertEquals(eur("300"), total.rows[1].value)
        assertEquals("3", total.rows[2].value)
        assertTrue(total.rows.all { it.bold })
        assertFalse(cards[0].rows[0].bold)
        assertTrue(cards[0].rows[1].bold)
    }

    @Test
    fun `quarter cards with two currencies are one headed group per currency in currency order`() {
        whenever(query.getQuarterlyTotals(any(), any())).thenReturn(
            listOf(quarter(2026, 1, "50", "50", 1, Currency.CHF), quarter(2026, 1, "100", "119", 1)),
        )

        val groups = presenter.quarterCards(StatisticsFilter(), 2026)

        assertEquals(listOf("EUR", "CHF"), groups.map { it.heading })
    }

    @Test
    fun `active quarter only applies in quarter mode`() {
        val filter = StatisticsFilter(quarter = 2)
        assertEquals(2, presenter.activeQuarter(filter, StatisticsPeriod.QUARTER))
        assertNull(presenter.activeQuarter(filter, StatisticsPeriod.YEAR))
    }

    // ── Status cards ────────────────────────────────────────────────────────

    @Test
    fun `status cards count per status with shares and total`() {
        whenever(query.getStatusDistribution(any())).thenReturn(
            listOf(StatusDistribution(InvoiceStatus.DRAFT, 2), StatusDistribution(InvoiceStatus.ISSUED, 2)),
        )

        val cards = presenter.statusCards(StatisticsFilter(), 2026)

        assertEquals(6, cards.size)
        assertEquals(listOf(2L, 0L, 2L, 0L, 0L, 4L), cards.map { it.count })
        assertEquals(String.format(Locale.GERMANY, "%.1f%%", 50.0), cards[0].share)
        assertEquals("100%", cards[5].share)
        assertEquals(String.format(Locale.GERMANY, "%.1f%%", 0.0), cards[1].share)
    }

    @Test
    fun `status cards without data show dashes`() {
        whenever(query.getStatusDistribution(any())).thenReturn(emptyList())

        val cards = presenter.statusCards(StatisticsFilter(), 2026)

        assertTrue(cards.all { it.count == 0L && it.share == "—" })
    }

    @Test
    fun `pctOf handles zero total and zero share`() {
        assertEquals("—", InvoiceStatisticsPresenter.pctOf(0, 0))
        assertEquals(String.format(Locale.GERMANY, "%.1f%%", 100.0 / 3), InvoiceStatisticsPresenter.pctOf(1, 3))
    }

    @Test
    fun `context label per period`() {
        val p = InvoiceStatisticsPresenter
        assertEquals("Angezeigt für: Jahr 2025", p.buildContextLabel(StatisticsPeriod.YEAR, null, null, 2025))
        assertEquals("Angezeigt für: Jahr 2025", p.buildContextLabel(StatisticsPeriod.MONTH, null, null, 2025))
        assertEquals("Angezeigt für: Jahr 2025", p.buildContextLabel(StatisticsPeriod.QUARTER, null, null, 2025))
        assertEquals("Angezeigt für: März 2025", p.buildContextLabel(StatisticsPeriod.MONTH, 3, null, 2025))
        assertEquals("Angezeigt für: II (Apr–Juni) 2025", p.buildContextLabel(StatisticsPeriod.QUARTER, null, 2, 2025))
        assertEquals("Angezeigt für: Q9 2025", p.buildContextLabel(StatisticsPeriod.QUARTER, null, 9, 2025))
        assertEquals("Angezeigt für: März 2025", presenter.statusContext(StatisticsFilter(month = 3), StatisticsPeriod.MONTH, 2025))
    }

    // ── Trend ───────────────────────────────────────────────────────────────

    @Test
    fun `year trend has four quarter points drilling into quarters`() {
        whenever(query.getQuarterlyTotals(any(), any())).thenReturn(listOf(quarter(2026, 1, "100", "119", 3)))
        val drilled = mutableListOf<DrillTarget>()

        val model = presenter.trend(StatisticsFilter(), 2026, StatisticsPeriod.YEAR, drilled::add)

        assertEquals("Quartalsübersicht 2026", model.title)
        val points = model.series.single().points
        assertEquals(listOf("I", "II", "III", "IV"), points.map { it.label })
        assertEquals(DrillTarget(StatisticsPeriod.QUARTER, 3), points[2].drill)
        model.onDrill(points[2].drill!!)
        assertEquals(listOf(DrillTarget(StatisticsPeriod.QUARTER, 3)), drilled)
    }

    @Test
    fun `quarter mode without selection shows quarters, with selection that quarter's months`() {
        whenever(query.getQuarterlyTotals(any(), any())).thenReturn(emptyList())
        val noSel = presenter.trend(StatisticsFilter(), 2026, StatisticsPeriod.QUARTER, {})
        assertEquals("Quartalsübersicht 2026", noSel.title)
        assertEquals(4, noSel.series.single().points.size)

        whenever(query.getMonthlyTrend(any(), any())).thenReturn(
            listOf(MonthlyTrend(2026, 4, money("10"), money("12")), MonthlyTrend(2026, 8, money("1"), money("1"))),
        )
        val sel = presenter.trend(StatisticsFilter(quarter = 2), 2026, StatisticsPeriod.QUARTER, {})
        assertEquals("Monatsübersicht II (Apr–Juni) 2026", sel.title)
        val points = sel.series.single().points
        assertEquals(listOf("April", "Mai", "Juni"), points.map { it.label })
        assertEquals(money("12"), points[0].gross)
        assertEquals(DrillTarget(StatisticsPeriod.MONTH, 5), points[1].drill)
    }

    @Test
    fun `month mode without selection shows twelve short months, with selection the days`() {
        whenever(query.getMonthlyTrend(any(), any())).thenReturn(emptyList())
        val noSel = presenter.trend(StatisticsFilter(), 2026, StatisticsPeriod.MONTH, {})
        assertEquals("Monatsübersicht 2026", noSel.title)
        assertEquals(12, noSel.series.single().points.size)
        assertEquals(DrillTarget(StatisticsPeriod.MONTH, 1), noSel.series.single().points[0].drill)

        whenever(query.getDailyTrend(eq(2026), eq(2), any())).thenReturn(
            listOf(DailyTrend(2026, 2, 5, money("10"), money("12"))),
        )
        val sel = presenter.trend(StatisticsFilter(month = 2), 2026, StatisticsPeriod.MONTH, {})
        assertEquals("Tagesübersicht Februar 2026", sel.title)
        val points = sel.series.single().points
        assertEquals(28, points.size)
        assertEquals("5.", points[4].label)
        assertEquals(money("12"), points[4].gross)
        assertNull(points[4].drill)
    }

    @Test
    fun `trend with two currencies yields one series per currency`() {
        whenever(query.getMonthlyTrend(any(), any())).thenReturn(
            listOf(MonthlyTrend(2026, 1, money("1"), money("1"), Currency.CHF), MonthlyTrend(2026, 1, money("2"), money("2"))),
        )

        val model = presenter.trend(StatisticsFilter(), 2026, StatisticsPeriod.MONTH, {})

        assertEquals(setOf(Currency.EUR, Currency.CHF), model.series.map { it.currency }.toSet())
    }
}
