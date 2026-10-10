package de.littletrotter.ui.view.quote

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._find
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.router.BeforeEnterEvent
import de.littletrotter.application.quote.QuoteAvailability
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.inbound.QuoteDashboard
import de.littletrotter.application.quote.port.inbound.QuoteKpis
import de.littletrotter.application.quote.port.inbound.QuoteQuarterTotals
import de.littletrotter.application.quote.port.inbound.QuoteRankingKind
import de.littletrotter.application.quote.port.inbound.QuoteRankingQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteRankingRow
import de.littletrotter.application.quote.port.inbound.QuoteStatisticsDashboardQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteStatisticsSelection
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.application.quote.port.inbound.QuoteStatusCounts
import de.littletrotter.application.quote.port.inbound.QuoteTrendPoint
import de.littletrotter.application.security.AppPermission
import de.littletrotter.shared.Money
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.shared.time.ClockTimeSource
import de.littletrotter.ui.component.statistics.StatisticsPeriod
import de.littletrotter.ui.format.MoneyFormatter
import de.littletrotter.ui.view.invoice.InvoiceListView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

class QuoteStatisticsViewKaribuTest {

    private val dashboardQuery: QuoteStatisticsDashboardQueryPort = mock()
    private val rankingQuery: QuoteRankingQueryPort = mock()
    private val clock = ClockTimeSource.fixedAtLocal(LocalDateTime.parse("2026-10-07T09:00:00"))
    private val thisYear = clock.today().year

    private fun kpis(volume: String = "0.00", count: Int = 0) = QuoteKpis(
        sentVolume = Money.of(volume),
        sentCount = count,
        averageSent = if (count > 0) Money.of("30.00") else null,
        inProgressAmount = Money.of("5.00"),
        inProgressCount = 2,
        avgDaysToSend = if (count > 0) BigDecimal("2.5") else null,
    )

    private fun dashboard(years: List<Int> = listOf(thisYear, thisYear - 1), sent: Int = 3) = QuoteDashboard(
        availableYears = years,
        kpis = kpis("90.00", sent),
        kpisPreviousYear = kpis("45.00", 1),
        statusCounts = QuoteStatusCounts(draft = 1, review = 1, sent = sent, cancelled = 0),
        quarters = listOf(QuoteQuarterTotals(2, Money.of("60.00"), 2), QuoteQuarterTotals(3, Money.of("30.00"), 1)),
        trend = listOf(
            QuoteTrendPoint(LocalDate.of(thisYear, 4, 1), Money.of("40.00"), 1),
            QuoteTrendPoint(LocalDate.of(thisYear, 5, 1), Money.of("20.00"), 1),
        ),
    )

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        registerQuoteRoutes()
        whenever(dashboardQuery.dashboard(any())).thenAnswer { dashboard() }
        whenever(rankingQuery.ranking(any(), any(), any())).thenAnswer {
            listOf(QuoteRankingRow("k", if (it.getArgument<QuoteRankingKind>(0) == QuoteRankingKind.CUSTOMER) "Acme GmbH" else "Beratung", 2, Money.of("90.00")))
        }
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun enter(enabled: Boolean = true, vararg granted: AppPermission = ALL_QUOTE_PERMISSIONS): Pair<QuoteStatisticsView, BeforeEnterEvent> {
        val view = QuoteStatisticsView(dashboardQuery, rankingQuery, QuoteAvailability(enabled), rolesWith(*granted), clock)
        val event = quoteEvent()
        UI.getCurrent().add(view)
        view.beforeEnter(event)
        return view to event
    }

    private fun texts(view: QuoteStatisticsView): List<String> = view._find<Span>().map { it.text }

    private fun selectionsAsked(): List<QuoteStatisticsSelection> {
        val captor = org.mockito.kotlin.argumentCaptor<QuoteStatisticsSelection>()
        verify(dashboardQuery, org.mockito.kotlin.atLeastOnce()).dashboard(captor.capture())
        return captor.allValues
    }

    private fun quarterCards(view: QuoteStatisticsView): List<Div> =
        view.revenueMatrix.children.toList().filterIsInstance<Div>().first { it.hasClassName("revenue-card-row") }
            .children.toList().filterIsInstance<Div>()

    private fun trendColumns(view: QuoteStatisticsView): List<VerticalLayout> =
        view.trendChart.children.toList().filterIsInstance<Div>().first()
            .children.toList().filterIsInstance<Div>()[1].children.toList().filterIsInstance<VerticalLayout>()

    @Test
    fun `without the component or the permission the view forwards to the invoice list`() {
        val (_, off) = enter(enabled = false)
        verify(off).forwardTo(InvoiceListView::class.java)

        val (_, denied) = enter(true, AppPermission.QUOTE_EDIT)
        verify(denied).forwardTo(InvoiceListView::class.java)
        verify(dashboardQuery, never()).dashboard(any())
        verify(rankingQuery, never()).ranking(any(), any(), any())
    }

    @Test
    fun `entering shows the key figures, quarter cards, status cards, trend and rankings`() {
        val (view, _) = enter()

        assertEquals(thisYear, view.yearSelect.value)
        assertEquals(StatisticsPeriod.YEAR, view.periodSelect.value)
        val shown = texts(view)
        assertTrue(MoneyFormatter.format(Money.of("90.00")) in shown, shown.toString())
        assertTrue("Versendet (netto)" in shown)
        assertTrue("2,5" in shown)
        assertTrue(shown.any { it.contains("ggü. VJ") })
        assertTrue("Entwurf" in shown && "Prüfung" in shown && "Versendet" in shown && "Storniert" in shown && "Gesamt" in shown)
        assertEquals(5, quarterCards(view).size)
        assertEquals(4, trendColumns(view).size)
        assertTrue(view.customerTable._find<Span>().any { it.text == "Acme GmbH" })
        assertTrue(view.positionTable._find<Span>().any { it.text == "Beratung" })
        assertFalse(view.emptyState.isVisible)
        assertEquals(1, selectionsAsked().size)
    }

    @Test
    fun `a year switch asks for that year`() {
        val (view, _) = enter()

        view.yearSelect.value = thisYear - 1

        assertEquals(thisYear - 1, selectionsAsked().last().year)
        assertEquals(thisYear - 1, view.yearSelect.value)
    }

    @Test
    fun `a current year without offers falls back to the newest year that has some`() {
        whenever(dashboardQuery.dashboard(any())).thenAnswer { dashboard(years = listOf(thisYear - 2)) }

        val (view, _) = enter()

        assertEquals(thisYear - 2, view.yearSelect.value)
        assertEquals(thisYear - 2, selectionsAsked().last().year)
    }

    @Test
    fun `no offers at all keep the current year and show the empty note`() {
        whenever(dashboardQuery.dashboard(any())).thenAnswer {
            QuoteDashboard(emptyList(), kpis(), kpis(), QuoteStatusCounts(0, 0, 0, 0), emptyList(), emptyList())
        }
        whenever(rankingQuery.ranking(any(), any(), any())).thenReturn(emptyList())

        val (view, _) = enter()

        assertEquals(thisYear, view.yearSelect.value)
        assertTrue(view.emptyState.isVisible)
        assertTrue(texts(view).contains("–"))
        assertTrue(view.customerTable._find<Span>().any { it.text == "Keine Daten vorhanden" })
    }

    @Test
    fun `the filters narrow the selection`() {
        val (view, _) = enter()

        view.statusFilter.value = QuoteStatus.DRAFT
        view.customerTypeFilter.value = CustomerType.CONSUMER

        val last = selectionsAsked().last()
        assertEquals(QuoteStatus.DRAFT, last.status)
        assertEquals(CustomerType.CONSUMER, last.customerType)
        verify(rankingQuery).ranking(QuoteRankingKind.CUSTOMER, last, 10)
        view.statusFilter.clear()
        assertNull(selectionsAsked().last().status)
    }

    @Test
    fun `a quarter card drills into the quarter, a trend bar into the month, the breadcrumb leads back`() {
        val (view, _) = enter()

        quarterCards(view)[1]._click()

        assertEquals(StatisticsPeriod.QUARTER, view.periodSelect.value)
        assertEquals(2, view.quarterSelect.value)
        assertTrue(view.quarterSelect.isVisible)
        assertEquals(2, selectionsAsked().last().quarter)
        assertEquals(3, trendColumns(view).size)
        assertTrue(view.breadcrumbs._find<Button>().any { it.text.contains("II") })

        trendColumns(view)[1]._click()

        assertEquals(StatisticsPeriod.MONTH, view.periodSelect.value)
        assertEquals(5, view.monthSelect.value)
        assertEquals(5, selectionsAsked().last().month)
        assertNull(selectionsAsked().last().quarter)
        assertEquals(31, trendColumns(view).size)
        val quarterCrumb = view.breadcrumbs._find<Button>().first { it.text.contains("II") }

        quarterCrumb._click()

        assertEquals(StatisticsPeriod.QUARTER, view.periodSelect.value)
        assertEquals(2, view.quarterSelect.value)
        assertNull(view.monthSelect.value)

        view.breadcrumbs._find<Button>().first()._click()

        assertEquals(StatisticsPeriod.YEAR, view.periodSelect.value)
        assertNull(selectionsAsked().last().quarter)
        assertEquals(4, trendColumns(view).size)
    }

    @Test
    fun `choosing the month period without a month shows the year by months`() {
        val (view, _) = enter()

        view.periodSelect.value = StatisticsPeriod.MONTH

        assertTrue(view.monthSelect.isVisible)
        assertFalse(view.quarterSelect.isVisible)
        assertEquals(12, trendColumns(view).size)
        assertTrue(texts(view).contains("Alle Monate"))

        view.periodSelect.value = StatisticsPeriod.QUARTER
        assertTrue(texts(view).contains("Alle Quartale"))
    }

    @Test
    fun `the refresh button asks again`() {
        val (view, _) = enter()

        view.refreshButton._click()

        assertEquals(2, selectionsAsked().size)
    }

    @Test
    fun `an unreachable component leaves the sections empty`() {
        whenever(dashboardQuery.dashboard(any())).thenThrow(QuoteServiceException("down"))

        val (view, _) = enter()

        assertTrue(view.kpiRow.children.toList().isEmpty())
        verify(rankingQuery, never()).ranking(any(), any(), any())
    }

    @Test
    fun `a failing year switch keeps the shown figures`() {
        val (view, _) = enter()
        whenever(dashboardQuery.dashboard(any())).thenThrow(QuoteServiceException("down"))
        whenever(rankingQuery.ranking(any(), any(), any())).thenThrow(QuoteServiceException("down"))

        view.yearSelect.value = thisYear - 1

        assertEquals(5, view.kpiRow.children.toList().size)
    }

    @Test
    fun `clearing the year does not ask again`() {
        val (view, _) = enter()

        view.yearSelect.clear()

        assertEquals(1, selectionsAsked().size)
    }

    @Test
    fun `a trend bar of the year view drills into the quarter`() {
        val (view, _) = enter()

        trendColumns(view)[1]._click()

        assertEquals(StatisticsPeriod.QUARTER, view.periodSelect.value)
        assertEquals(2, view.quarterSelect.value)
    }

    @Test
    fun `a trend bar of the month view selects the month directly`() {
        val (view, _) = enter()
        view.periodSelect.value = StatisticsPeriod.MONTH

        trendColumns(view)[4]._click()

        assertEquals(5, view.monthSelect.value)
        assertEquals(5, selectionsAsked().last().month)
    }

    @Test
    fun `clearing the period falls back to the year view`() {
        val (view, _) = enter()
        view.periodSelect.value = StatisticsPeriod.QUARTER

        view.periodSelect.clear()

        assertNull(selectionsAsked().last().quarter)
        assertEquals(4, trendColumns(view).size)
    }

    @Test
    fun `status counts without sent offers still hide the empty note`() {
        whenever(dashboardQuery.dashboard(any())).thenAnswer { dashboard(sent = 0) }

        val (view, _) = enter()

        assertFalse(view.emptyState.isVisible)
    }
}
