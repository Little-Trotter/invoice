package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.ClickEvent
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.ComponentUtil
import com.vaadin.flow.component.button.Button
import de.littletrotter.ui.component.statistics.StatisticsPeriod
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import de.littletrotter.application.invoice.port.inbound.InvoiceStatisticsQueryPort
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * [InvoiceStatisticsView] drill-down on an empty year (every statistics query returns
 * nothing, which still renders four all-zero quarter cards): a quarter card drills into that
 * quarter, and the breadcrumb buttons navigate back to the quarter or the whole year.
 */
class InvoiceStatisticsViewBoundaryKaribuTest {

    private val statisticsQuery: InvoiceStatisticsQueryPort = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private inline fun <reified T : Any> field(v: InvoiceStatisticsView, name: String): T {
        val f = InvoiceStatisticsView::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(v).requireAs()
    }

    private fun periodSelect(v: InvoiceStatisticsView): ComboBox<StatisticsPeriod> = field(v, "periodSelect")
    private fun quarterSelect(v: InvoiceStatisticsView): ComboBox<Int> = field(v, "quarterSelect")
    private fun monthSelect(v: InvoiceStatisticsView): ComboBox<Int> = field(v, "monthSelect")
    private fun crumbButtons(v: InvoiceStatisticsView): List<Button> =
        field<HorizontalLayout>(v, "breadcrumbs").children.toList().filterIsInstance<Button>()

    private fun Component.descendants(): List<Component> = children.toList().flatMap { listOf(it) + it.descendants() }

    private fun quarterCards(v: InvoiceStatisticsView): List<Div> =
        v.descendants().filterIsInstance<Div>().filter { it.hasClassName("revenue-card--quarter") }

    private fun click(component: Component) = ComponentUtil.fireEvent(component, ClickEvent(component))

    @Test
    fun `happy path - clicking a quarter card of an empty year drills into that quarter`() {
        val v = InvoiceStatisticsView(statisticsQuery, time = FIXED_TIME_SOURCE)
        val cards = quarterCards(v)
        assertEquals(4, cards.size, "an empty year still shows four all-zero quarter cards")

        click(cards[2])

        assertEquals(StatisticsPeriod.QUARTER, periodSelect(v).value)
        assertEquals(3, quarterSelect(v).value)
    }

    @Test
    fun `boundary - the year crumb returns from a chosen quarter to the whole year`() {
        val v = InvoiceStatisticsView(statisticsQuery, time = FIXED_TIME_SOURCE)
        periodSelect(v).value = StatisticsPeriod.QUARTER
        quarterSelect(v).value = 2

        crumbButtons(v).first().click()

        assertEquals(StatisticsPeriod.YEAR, periodSelect(v).value)
        assertFalse(quarterSelect(v).isVisible)
        assertFalse(monthSelect(v).isVisible)
    }

    @Test
    fun `boundary - the quarter crumb keeps the chosen quarter`() {
        val v = InvoiceStatisticsView(statisticsQuery, time = FIXED_TIME_SOURCE)
        periodSelect(v).value = StatisticsPeriod.QUARTER
        quarterSelect(v).value = 4

        crumbButtons(v).last().click()

        assertEquals(StatisticsPeriod.QUARTER, periodSelect(v).value)
        assertEquals(4, quarterSelect(v).value)
    }

    @Test
    fun `boundary - the year period offers only the year crumb`() {
        val v = InvoiceStatisticsView(statisticsQuery, time = FIXED_TIME_SOURCE)
        periodSelect(v).value = StatisticsPeriod.YEAR

        assertEquals(1, crumbButtons(v).size)
    }
}
