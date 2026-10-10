package de.littletrotter.ui.component.statistics

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class TrendChartKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun money(v: String) = Money(BigDecimal(v))

    private fun point(label: String, net: String, gross: String, drill: DrillTarget? = null) =
        TrendPoint(label, money(net), money(gross), drill)

    private fun bodies(chart: TrendChart) = chart.children.toList().filterIsInstance<Div>()

    private fun columns(body: Div): List<VerticalLayout> =
        body.children.toList().filterIsInstance<Div>()[1].children.toList().filterIsInstance<VerticalLayout>()

    @Test
    fun `an empty model renders only the no-data hint`() {
        val chart = TrendChart()
        chart.render(TrendModel("t", emptyList(), {}))

        assertEquals(1, chart.children.toList().size)
        assertTrue(chart.children.toList().single() is Span)
    }

    @Test
    fun `renders the title, one body per series and one column per point`() {
        val chart = TrendChart()
        chart.render(TrendModel("Quartalsübersicht 2026", listOf(TrendSeries(Currency.EUR, listOf(point("I", "1", "2"), point("II", "0", "0")))), {}))

        val header = chart.children.toList().filterIsInstance<HorizontalLayout>().first()
        assertEquals("Quartalsübersicht 2026", header.children.toList().filterIsInstance<Span>().first().text)
        assertEquals(1, bodies(chart).size)
        assertEquals(2, columns(bodies(chart).single()).size)
    }

    @Test
    fun `value label is blank for a zero gross and the all-zero axis falls back to 100`() {
        val chart = TrendChart()
        chart.render(TrendModel("t", listOf(TrendSeries(Currency.EUR, listOf(point("I", "0", "0")))), {}))

        val col = columns(bodies(chart).single()).single()
        assertEquals("", col.children.toList().filterIsInstance<Span>().first().text)
    }

    @Test
    fun `a series without points still renders an empty section`() {
        val chart = TrendChart()
        chart.render(TrendModel("t", listOf(TrendSeries(Currency.EUR, emptyList())), {}))

        assertEquals(0, columns(bodies(chart).single()).size)
    }

    @Test
    fun `two series are ordered by currency and each headed by its code`() {
        val chart = TrendChart()
        chart.render(
            TrendModel(
                "t",
                listOf(TrendSeries(Currency.CHF, listOf(point("a", "1", "1"))), TrendSeries(Currency.EUR, listOf(point("a", "1", "1")))),
                {},
            ),
        )

        assertEquals(listOf("EUR", "CHF"), chart.children.toList().filterIsInstance<Span>().map { it.text })
        assertEquals(2, bodies(chart).size)
    }

    @Test
    fun `clicking a drillable column reports its target, a plain column does nothing`() {
        val drilled = mutableListOf<DrillTarget>()
        val target = DrillTarget(StatisticsPeriod.MONTH, 4)
        val chart = TrendChart()
        chart.render(TrendModel("t", listOf(TrendSeries(Currency.EUR, listOf(point("a", "1", "1", target), point("b", "1", "1")))), drilled::add))

        val (first, second) = columns(bodies(chart).single())
        first._click()
        second._click()

        assertEquals(listOf(target), drilled)
        assertTrue(first.hasClassName("lt-trend-chart__column--drillable"))
        assertTrue(second.hasClassName("lt-trend-chart__column--drillable").not())
    }

    @Test
    fun `dense charts use narrow columns`() {
        val points = (1..31).map { point("$it.", "1", "1") }
        val chart = TrendChart()
        chart.render(TrendModel("t", listOf(TrendSeries(Currency.EUR, points)), {}))

        assertTrue(columns(bodies(chart).single()).first().hasClassName("lt-trend-chart__column--dense"))
    }
}
