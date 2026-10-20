package de.littletrotter.ui.view.quote

import de.littletrotter.application.quote.port.inbound.QuoteDashboard
import de.littletrotter.application.quote.port.inbound.QuoteKpis
import de.littletrotter.application.quote.port.inbound.QuoteQuarterTotals
import de.littletrotter.application.quote.port.inbound.QuoteRankingRow
import de.littletrotter.application.quote.port.inbound.QuoteStatusCounts
import de.littletrotter.application.quote.port.inbound.QuoteTrendPoint
import de.littletrotter.shared.Money
import de.littletrotter.ui.component.statistics.DrillTarget
import de.littletrotter.ui.component.statistics.StatisticsPeriod
import de.littletrotter.ui.format.MoneyFormatter
import de.littletrotter.ui.view.quote.QuoteStatisticsPresenter.PeriodSelection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class QuoteStatisticsPresenterTest {

    private val presenter = QuoteStatisticsPresenter()

    private fun kpis(volume: String, count: Int, average: String? = null, days: String? = null) = QuoteKpis(
        sentVolume = Money.of(volume),
        sentCount = count,
        averageSent = average?.let { Money.of(it) },
        inProgressAmount = Money.of("12.00"),
        inProgressCount = 3,
        avgDaysToSend = days?.let { BigDecimal(it) },
    )

    private fun dashboard(
        now: QuoteKpis = kpis("100.00", 4, "25.00", "2.0"),
        before: QuoteKpis = kpis("50.00", 2, "20.00", "3.5"),
        counts: QuoteStatusCounts = QuoteStatusCounts(1, 1, 4, 2),
        quarters: List<QuoteQuarterTotals> = listOf(QuoteQuarterTotals(1, Money.of("40.00"), 1), QuoteQuarterTotals(3, Money.of("60.00"), 3)),
        trend: List<QuoteTrendPoint> = listOf(
            QuoteTrendPoint(LocalDate.of(2026, 2, 1), Money.of("10.00"), 1),
            QuoteTrendPoint(LocalDate.of(2026, 2, 14), Money.of("7.00"), 1),
        ),
    ) = QuoteDashboard(listOf(2026), now, before, counts, quarters, trend)

    @Test
    fun `key figures carry the comparison with the previous year`() {
        val tiles = presenter.kpiTiles(dashboard())

        assertEquals(listOf("Versendet (netto)", "Versendete Angebote", "Ø Angebotswert", "In Arbeit", "Ø Tage bis Versand"), tiles.map { it.label })
        assertEquals(MoneyFormatter.format(Money.of("100.00")), tiles[0].value)
        assertEquals("↑ +100,0% ggü. VJ", tiles[0].delta!!.text)
        assertTrue(tiles[0].delta!!.positive)
        assertEquals("4", tiles[1].value)
        assertEquals("↑ +2 ggü. VJ", tiles[1].delta!!.text)
        assertEquals("↑ +25,0% ggü. VJ", tiles[2].delta!!.text)
        assertEquals(MoneyFormatter.format(Money.of("12.00")), tiles[3].value)
        assertEquals("3 Angebote", tiles[3].caption)
        assertNull(tiles[3].delta)
        assertEquals("2", tiles[4].value)
        assertEquals("↓ -1,5 ggü. VJ", tiles[4].delta!!.text)
        assertTrue(tiles[4].delta!!.positive)
    }

    @Test
    fun `falling figures read as a decrease and longer sending as the wrong direction`() {
        val tiles = presenter.kpiTiles(
            dashboard(now = kpis("10.00", 1, "10.00", "5.0"), before = kpis("50.00", 3, "20.00", "3.5")),
        )

        assertFalse(tiles[0].delta!!.positive)
        assertTrue(tiles[0].delta!!.text.startsWith("↓ -80,0%"))
        assertEquals("↓ -2 ggü. VJ", tiles[1].delta!!.text)
        assertFalse(tiles[1].delta!!.positive)
        assertFalse(tiles[2].delta!!.positive)
        assertEquals("↑ +1,5 ggü. VJ", tiles[4].delta!!.text)
        assertFalse(tiles[4].delta!!.positive)
    }

    @Test
    fun `nothing sent shows dashes and no comparison`() {
        val tiles = presenter.kpiTiles(dashboard(now = kpis("0.00", 0), before = kpis("0.00", 0)))

        assertNull(tiles[0].delta)
        assertNull(tiles[1].delta)
        assertEquals("–", tiles[2].value)
        assertNull(tiles[2].delta)
        assertEquals("–", tiles[4].value)
        assertNull(tiles[4].delta)
    }

    @Test
    fun `an average without a previous-year average has no comparison`() {
        val tiles = presenter.kpiTiles(dashboard(now = kpis("10.00", 1, "10.00", "1.0"), before = kpis("0.00", 0)))

        assertNull(tiles[2].delta)
        assertNull(tiles[4].delta)
        assertEquals("↑ +1 ggü. VJ", tiles[1].delta!!.text)
        assertNull(tiles[0].delta)
    }

    @Test
    fun `quarter cards list four quarters and a total with bars relative to the best quarter`() {
        val group = presenter.quarterCards(dashboard()).single()

        assertNull(group.heading)
        assertEquals(listOf("Q1", "Q2", "Q3", "Q4", "Gesamt"), group.cards.map { it.label })
        assertEquals(listOf(66, 0, 100, 0), group.cards.take(4).map { it.barShare })
        assertEquals(listOf(1, 2, 3, 4), group.cards.take(4).map { it.drillKey })
        assertEquals(MoneyFormatter.format(Money.of("100.00")), group.cards.last().rows[0].value)
        assertEquals("4", group.cards.last().rows[1].value)
        assertTrue(group.cards.last().primary)
        assertNull(group.cards.last().drillKey)
    }

    @Test
    fun `quarter cards without any sent volume have empty bars`() {
        val group = presenter.quarterCards(dashboard(quarters = emptyList())).single()

        assertEquals(listOf(0, 0, 0, 0), group.cards.take(4).map { it.barShare })
    }

    @Test
    fun `the active quarter only counts in quarter mode`() {
        assertEquals(2, presenter.activeQuarter(StatisticsPeriod.QUARTER, 2))
        assertNull(presenter.activeQuarter(StatisticsPeriod.QUARTER, null))
        assertNull(presenter.activeQuarter(StatisticsPeriod.MONTH, 2))
        assertNull(presenter.activeQuarter(StatisticsPeriod.YEAR, 2))
    }

    @Test
    fun `status cards give each stage its share of the total`() {
        val cards = presenter.statusCards(QuoteStatusCounts(1, 1, 4, 2))

        assertEquals(listOf("Entwurf", "Prüfung", "Versendet", "Storniert", "Gesamt"), cards.map { it.label })
        assertEquals(listOf(1L, 1L, 4L, 2L, 8L), cards.map { it.count })
        assertEquals("50,0%", cards[2].share)
        assertEquals("100%", cards.last().share)
    }

    @Test
    fun `status cards without offers show dashes`() {
        val cards = presenter.statusCards(QuoteStatusCounts(0, 0, 0, 0))

        assertTrue(cards.all { it.share == "—" })
    }

    @Test
    fun `the context caption names the selected span`() {
        assertEquals("Angezeigt für: Jahr 2026", presenter.statusContext(StatisticsPeriod.YEAR, null, null, 2026))
        assertEquals("Angezeigt für: Jahr 2026", presenter.statusContext(StatisticsPeriod.MONTH, null, null, 2026))
        assertEquals("Angezeigt für: Jahr 2026", presenter.statusContext(StatisticsPeriod.QUARTER, null, null, 2026))
        assertEquals("Angezeigt für: März 2026", presenter.statusContext(StatisticsPeriod.MONTH, 3, null, 2026))
        assertEquals("Angezeigt für: II (Apr–Juni) 2026", presenter.statusContext(StatisticsPeriod.QUARTER, null, 2, 2026))
    }

    @Test
    fun `the year trend shows quarters that drill into the quarter`() {
        val model = presenter.trend(dashboard(), StatisticsPeriod.YEAR, PeriodSelection(2026, null, null)) {}

        assertEquals("Versendet (netto) je Quartal 2026", model.title)
        assertFalse(model.showGross)
        val points = model.series.single().points
        assertEquals(listOf("Q1", "Q2", "Q3", "Q4"), points.map { it.label })
        assertEquals(Money.of("60.00"), points[2].net)
        assertEquals(DrillTarget(StatisticsPeriod.QUARTER, 3), points[2].drill)
    }

    @Test
    fun `a quarter without a number falls back to the quarters of the year`() {
        val model = presenter.trend(dashboard(), StatisticsPeriod.QUARTER, PeriodSelection(2026, null, null)) {}

        assertEquals("Versendet (netto) je Quartal 2026", model.title)
        assertEquals(4, model.series.single().points.size)
    }

    @Test
    fun `a quarter trend shows its three months that drill into the month`() {
        val model = presenter.trend(dashboard(), StatisticsPeriod.QUARTER, PeriodSelection(2026, 1, null)) {}

        assertEquals("Versendet (netto) je Monat I (Jan–März) 2026", model.title)
        val points = model.series.single().points
        assertEquals(listOf("Januar", "Februar", "März"), points.map { it.label })
        assertEquals(Money.of("10.00"), points[1].net)
        assertEquals(DrillTarget(StatisticsPeriod.MONTH, 2), points[1].drill)
    }

    @Test
    fun `a month trend shows each day without drill`() {
        val model = presenter.trend(dashboard(), StatisticsPeriod.MONTH, PeriodSelection(2026, null, 2)) {}

        assertEquals("Versendet (netto) je Tag Februar 2026", model.title)
        val points = model.series.single().points
        assertEquals(28, points.size)
        assertEquals("14.", points[13].label)
        assertEquals(Money.of("7.00"), points[13].net)
        assertNull(points[13].drill)
    }

    @Test
    fun `the month period without a month shows the twelve months of the year`() {
        val model = presenter.trend(dashboard(), StatisticsPeriod.MONTH, PeriodSelection(2026, null, null)) {}

        assertEquals("Versendet (netto) je Monat 2026", model.title)
        val points = model.series.single().points
        assertEquals(12, points.size)
        assertEquals(Money.of("10.00"), points[1].net)
        assertEquals(Money.ZERO, points[0].net)
    }

    @Test
    fun `ranking rows show net, count and the share of the listed net`() {
        val rows = listOf(QuoteRankingRow("a", "Acme", 3, Money.of("75.00")), QuoteRankingRow("b", "Beta", 1, Money.of("25.00")))

        assertEquals(listOf("Acme", MoneyFormatter.format(Money.of("75.00")), "3", "75,0%"), presenter.customerRow(rows[0], rows))
        assertEquals(listOf("Beta", MoneyFormatter.format(Money.of("25.00")), "1"), presenter.positionRow(rows[1]))
    }

    @Test
    fun `a ranking of zero volume has a zero share`() {
        val rows = listOf(QuoteRankingRow("a", "Acme", 1, Money.ZERO))

        assertEquals("0,0%", presenter.customerRow(rows[0], rows)[3])
    }
}
