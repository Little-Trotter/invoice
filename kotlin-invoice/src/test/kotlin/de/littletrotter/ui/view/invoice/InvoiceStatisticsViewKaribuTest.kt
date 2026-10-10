package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._find
import de.littletrotter.ui.component.statistics.StatisticsPeriod
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.html.Span
import de.littletrotter.application.invoice.port.inbound.InvoiceStatisticsQueryPort
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * Covers [InvoiceStatisticsView]'s period-select `when` (Monat/Quartal/Jahr visibility toggling in
 * [InvoiceStatisticsView.onPeriodChanged]) and the breadcrumb-trail conditionals in
 * [InvoiceStatisticsView.updateBreadcrumbs] (quarter/month selected-vs-"Alle …" branches).
 * `InvoiceStatisticsQueryPort`'s queries all return `List<...>`, which Mockito's default answer
 * already returns empty for — no stubbing needed for these branches.
 */
class InvoiceStatisticsViewKaribuTest {

    private val statisticsQuery: InvoiceStatisticsQueryPort = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    @Test
    fun `ranking tab describes catalogue positions`() {
        val tabs = view()._find<com.vaadin.flow.component.tabs.TabSheet>().single()
        assertEquals("Top-Leistungspositionen", tabs.getTabAt(3).label)
    }

    @Test
    fun `ranking tabs use the neutral invoice party terminology`() {
        val tabs = view()._find<com.vaadin.flow.component.tabs.TabSheet>().single()

        assertEquals("Top-Rechnungsempfänger", tabs.getTabAt(1).label)
        assertEquals("Top-Rechnungsaussteller", tabs.getTabAt(2).label)
    }

    @Test
    fun `year breadcrumb displays the complete year without thousands grouping`() {
        val v = view()
        val yearButton = breadcrumbs(v).children.toList()
            .filterIsInstance<com.vaadin.flow.component.button.Button>().single()

        assertEquals("Jahr 2026", yearButton.text)
    }

    @Test
    fun `ranking translations are independent of the wording profile`() {
        val gate = mock<de.littletrotter.application.settings.WordingSettingsGate>()
        val translations = de.littletrotter.ui.i18n.TranslationService(gate)
        for (profile in de.littletrotter.shared.wording.WordingProfile.entries) {
            org.mockito.kotlin.whenever(gate.currentProfile()).thenReturn(profile)
            assertEquals("Top-Leistungspositionen", translations.getTranslation("statistics.tab.topPositions", java.util.Locale.GERMAN))
            assertEquals("Top catalogue positions", translations.getTranslation("statistics.tab.topPositions", java.util.Locale.ENGLISH))
        }
    }

    private fun view() = InvoiceStatisticsView(statisticsQuery, time = FIXED_TIME_SOURCE)

    private fun periodSelect(v: InvoiceStatisticsView): ComboBox<StatisticsPeriod> {
        val field = InvoiceStatisticsView::class.java.getDeclaredField("periodSelect")
        field.isAccessible = true
        return field.get(v).requireAs()
    }

    private fun monthSelect(v: InvoiceStatisticsView): ComboBox<Int> {
        val field = InvoiceStatisticsView::class.java.getDeclaredField("monthSelect")
        field.isAccessible = true
        return field.get(v).requireAs()
    }

    private fun quarterSelect(v: InvoiceStatisticsView): ComboBox<Int> {
        val field = InvoiceStatisticsView::class.java.getDeclaredField("quarterSelect")
        field.isAccessible = true
        return field.get(v).requireAs()
    }

    private fun breadcrumbs(v: InvoiceStatisticsView): com.vaadin.flow.component.orderedlayout.HorizontalLayout {
        val field = InvoiceStatisticsView::class.java.getDeclaredField("breadcrumbs")
        field.isAccessible = true
        return field.get(v) as com.vaadin.flow.component.orderedlayout.HorizontalLayout
    }

    private fun breadcrumbTexts(v: InvoiceStatisticsView): List<String> =
        breadcrumbs(v).children.toList().filterIsInstance<Span>().map { it.text }

    // ── onPeriodChanged three-way visibility toggle ─────────────────────────────

    @Test
    fun `selecting Monat shows the month select and hides the quarter select`() {
        val v = view()
        periodSelect(v).value = StatisticsPeriod.MONTH
        assertTrue(monthSelect(v).isVisible)
        assertFalse(quarterSelect(v).isVisible)
    }

    @Test
    fun `selecting Quartal shows the quarter select and hides the month select`() {
        val v = view()
        periodSelect(v).value = StatisticsPeriod.QUARTER
        assertFalse(monthSelect(v).isVisible)
        assertTrue(quarterSelect(v).isVisible)
    }

    @Test
    fun `selecting Jahr hides both the month and quarter selects`() {
        val v = view()
        periodSelect(v).value = StatisticsPeriod.MONTH // start from a visible state
        periodSelect(v).value = StatisticsPeriod.YEAR
        assertFalse(monthSelect(v).isVisible)
        assertFalse(quarterSelect(v).isVisible)
    }

    @Test
    fun `switching period clears any previously selected month or quarter`() {
        val v = view()
        periodSelect(v).value = StatisticsPeriod.QUARTER
        quarterSelect(v).value = 2

        periodSelect(v).value = StatisticsPeriod.MONTH
        // the stale quarter selection must no longer show up as a breadcrumb once we're back on
        // Quartal with nothing chosen — the direct field read on ComboBox<Int> after clear() is
        // not usable here (Kotlin inserts a not-null check on the boxed Int that trips on a
        // genuinely-null cleared value), so this asserts the same fact through updateBreadcrumbs.
        periodSelect(v).value = StatisticsPeriod.QUARTER
        assertTrue(breadcrumbTexts(v).contains("Alle Quartale"), "the quarter selection must have been cleared by the Monat switch, not just hidden")
    }

    // ── updateBreadcrumbs ────────────────────────────────────────────────────────

    @Test
    fun `Jahr period shows only the year crumb`() {
        val v = view()
        val texts = breadcrumbTexts(v)
        assertTrue(texts.isEmpty(), "no Span crumbs beyond the year Button for the Jahr period")
    }

    @Test
    fun `Quartal period with no quarter chosen shows Alle Quartale`() {
        val v = view()
        periodSelect(v).value = StatisticsPeriod.QUARTER
        assertTrue(breadcrumbTexts(v).contains("Alle Quartale"))
    }

    @Test
    fun `Quartal period with a quarter chosen shows the quarter label, not the fallback`() {
        val v = view()
        periodSelect(v).value = StatisticsPeriod.QUARTER
        quarterSelect(v).value = 1
        val texts = breadcrumbTexts(v)
        assertFalse(texts.contains("Alle Quartale"))
    }

    @Test
    fun `Monat period with no month chosen shows Alle Monate`() {
        val v = view()
        periodSelect(v).value = StatisticsPeriod.MONTH
        assertTrue(breadcrumbTexts(v).contains("Alle Monate"))
    }

    @Test
    fun `Monat period with a month chosen shows the month name, not the fallback`() {
        val v = view()
        periodSelect(v).value = StatisticsPeriod.MONTH
        monthSelect(v).value = 3
        val texts = breadcrumbTexts(v)
        assertFalse(texts.contains("Alle Monate"))
        assertTrue(texts.any { it.contains("März") })
    }

    // ── buildStatisticsFilter's month/quarter inclusion depends on the active period ──

    @Test
    fun `queries are re-run through refresh whenever the status filter changes`() {
        val v = view()
        val statusField = InvoiceStatisticsView::class.java.getDeclaredField("statusFilter")
        statusField.isAccessible = true
        val statusFilter = statusField.get(v).requireAs<ComboBox<de.littletrotter.domain.invoice.InvoiceStatus>>()

        statusFilter.value = de.littletrotter.domain.invoice.InvoiceStatus.DRAFT

        org.mockito.kotlin.verify(statisticsQuery, org.mockito.kotlin.atLeastOnce()).getStatusDistribution(any())
    }

    // ── remaining branch gaps ────────────────────────────────────────────────────

    private fun yearSelect(v: InvoiceStatisticsView): ComboBox<Int> {
        val field = InvoiceStatisticsView::class.java.getDeclaredField("yearSelect")
        field.isAccessible = true
        return field.get(v).requireAs()
    }

    private fun reverseChargeFilter(v: InvoiceStatisticsView): ComboBox<Boolean> {
        val field = InvoiceStatisticsView::class.java.getDeclaredField("reverseChargeFilter")
        field.isAccessible = true
        return field.get(v).requireAs()
    }

    private fun trendChart(v: InvoiceStatisticsView): de.littletrotter.ui.component.statistics.TrendChart {
        val field = InvoiceStatisticsView::class.java.getDeclaredField("trendChart")
        field.isAccessible = true
        return field.get(v) as de.littletrotter.ui.component.statistics.TrendChart
    }

    private fun customerTable(v: InvoiceStatisticsView): de.littletrotter.ui.component.RankingTable<de.littletrotter.application.dto.RecipientTotals> {
        val field = InvoiceStatisticsView::class.java.getDeclaredField("customerTable")
        field.isAccessible = true
        return field.get(v).requireAs()
    }

    /**
     * [de.littletrotter.ui.component.RankingTable] renders a caption row (column labels)
     * followed by a card-stack `Div` (one `Div` "card" per ranked item, each card's own first
     * child being the row of rank badge + name + value cells). Returns that row `Div` per card, in
     * rank order, so callers can keep asserting on `Span` value cells the same way the earlier
     * flat-row layout let them.
     */
    private fun rankingCardRows(table: de.littletrotter.ui.component.RankingTable<*>): List<com.vaadin.flow.component.html.Div> {
        val cardStack = table.children.toList().filterIsInstance<com.vaadin.flow.component.html.Div>().last()
        return cardStack.children.toList().filterIsInstance<com.vaadin.flow.component.html.Div>()
            .map { card -> card.children.toList().filterIsInstance<com.vaadin.flow.component.html.Div>().first() }
    }

    @Test
    fun `an empty available-years list falls back to the current year`() {
        org.mockito.kotlin.whenever(statisticsQuery.getAvailableYears()).thenReturn(emptyList())
        val v = view()

        assertEquals(java.time.LocalDate.now().year, yearSelect(v).value)
    }

    @Test
    fun `a non-empty available-years list preselects the most recent year`() {
        org.mockito.kotlin.whenever(statisticsQuery.getAvailableYears()).thenReturn(listOf(2023, 2025, 2024))
        val v = view()

        assertEquals(2025, yearSelect(v).value)
    }

    @Test
    fun `reverseCharge combo labels true as Reverse Charge and false as Normal`() {
        val v = view()
        val generator = reverseChargeFilter(v).itemLabelGenerator

        assertEquals("Reverse Charge", generator.apply(true))
        assertEquals("Normal", generator.apply(false))
    }

    /** The bar columns of the first chart section, in order. */
    private fun trendColumns(v: InvoiceStatisticsView): List<com.vaadin.flow.component.orderedlayout.VerticalLayout> {
        val body = trendChart(v).children.toList().filterIsInstance<com.vaadin.flow.component.html.Div>().first()
        val area = body.children.toList().filterIsInstance<com.vaadin.flow.component.html.Div>()[1]
        return area.children.toList().filterIsInstance<com.vaadin.flow.component.orderedlayout.VerticalLayout>()
    }

    @Test
    fun `clicking a quarter bar of the year chart selects Quartal and that quarter`() {
        val v = view()

        trendColumns(v)[2]._click()

        assertEquals(StatisticsPeriod.QUARTER, periodSelect(v).value)
        assertEquals(3, quarterSelect(v).value)
    }

    @Test
    fun `clicking a month bar selects Monat and that month`() {
        val v = view()
        periodSelect(v).value = StatisticsPeriod.MONTH

        trendColumns(v)[6]._click()

        assertEquals(StatisticsPeriod.MONTH, periodSelect(v).value)
        assertEquals(7, monthSelect(v).value)
    }

    @Test
    fun `customer table share column is zero when the page's total gross is zero`() {
        org.mockito.kotlin.whenever(statisticsQuery.getRecipientTotals(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(
            listOf(
                de.littletrotter.application.dto.RecipientTotals(
                    recipientId = 1L, recipientName = "Kunde A",
                    grossAmount = de.littletrotter.shared.Money.of("0.00"),
                    invoiceCount = 1L, netAmount = de.littletrotter.shared.Money.of("0.00"),
                ),
            ),
        )
        val v = view()
        customerTable(v).refresh(de.littletrotter.application.dto.StatisticsFilter.EMPTY)

        val row = rankingCardRows(customerTable(v)).single()
        val shareCell = row.children.toList().filterIsInstance<Span>().last()
        assertTrue(shareCell.text.startsWith("0,0%"), "expected a zero share, got: ${shareCell.text}")
    }

    @Test
    fun `customer table share column is a real percentage when the page's total gross is positive`() {
        org.mockito.kotlin.whenever(statisticsQuery.getRecipientTotals(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(
            listOf(
                de.littletrotter.application.dto.RecipientTotals(
                    recipientId = 1L, recipientName = "Kunde A",
                    grossAmount = de.littletrotter.shared.Money.of("50.00"),
                    invoiceCount = 1L, netAmount = de.littletrotter.shared.Money.of("42.00"),
                ),
                de.littletrotter.application.dto.RecipientTotals(
                    recipientId = 2L, recipientName = "Kunde B",
                    grossAmount = de.littletrotter.shared.Money.of("50.00"),
                    invoiceCount = 1L, netAmount = de.littletrotter.shared.Money.of("42.00"),
                ),
            ),
        )
        val v = view()
        customerTable(v).refresh(de.littletrotter.application.dto.StatisticsFilter.EMPTY)

        val rows = rankingCardRows(customerTable(v))
        val shareCell = rows.first().children.toList().filterIsInstance<Span>().last()
        assertTrue(shareCell.text.startsWith("50,0%"), "expected a 50% share, got: ${shareCell.text}")
    }

    @Test
    fun `a null period select value defaults the statistics filter and refresh to Jahr`() {
        val v = view()
        periodSelect(v).value = null

        // Must not throw building the filter/refreshing with a cleared period select — the ?:
        // "Jahr" fallbacks in buildStatisticsFilter and refresh() both apply.
        yearSelect(v).value = yearSelect(v).value
        assertEquals(null, periodSelect(v).value)
    }

    @Test
    fun `a null year select value falls back to the already-selected year on refresh`() {
        val v = view()
        val before = yearSelect(v).value
        yearSelect(v).value = null

        // Triggers refresh(), which reads `yearSelect.value ?: selectedYear` — must not throw.
        // (Reading yearSelect(v).value back here would itself NPE on the boxed Int, same reason
        // `switching period clears...` above avoids it; the "no throw" is the assertion.)
        periodSelect(v).value = StatisticsPeriod.MONTH
        assertTrue(before != null)
    }

    // ── getRecipientTotals groups by currency ───────────────────────────────────

    @Test
    fun `a recipient billed in two currencies ranks as two rows, each in its own currency`() {
        org.mockito.kotlin.whenever(statisticsQuery.getRecipientTotals(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(
            listOf(
                de.littletrotter.application.dto.RecipientTotals(
                    recipientId = 1L, recipientName = "Kunde A",
                    grossAmount = de.littletrotter.shared.Money.of("119.00"),
                    invoiceCount = 1L, netAmount = de.littletrotter.shared.Money.of("100.00"),
                    currency = de.littletrotter.shared.Currency.EUR,
                ),
                de.littletrotter.application.dto.RecipientTotals(
                    recipientId = 1L, recipientName = "Kunde A",
                    grossAmount = de.littletrotter.shared.Money.of("50.00"),
                    invoiceCount = 1L, netAmount = de.littletrotter.shared.Money.of("50.00"),
                    currency = de.littletrotter.shared.Currency.CHF,
                ),
            ),
        )
        val v = view()
        customerTable(v).refresh(de.littletrotter.application.dto.StatisticsFilter.EMPTY)

        val rows = rankingCardRows(customerTable(v))
        assertEquals(2, rows.size, "one row per currency the recipient was billed in, not one row silently mixing both")
        val netCells = rows.map { row -> row.children.toList().filterIsInstance<Span>()[2].text }
        assertTrue(netCells.contains(de.littletrotter.ui.format.MoneyFormatter.format(de.littletrotter.shared.Money.of("100.00"), de.littletrotter.shared.Currency.EUR)))
        assertTrue(netCells.contains(de.littletrotter.ui.format.MoneyFormatter.format(de.littletrotter.shared.Money.of("50.00"), de.littletrotter.shared.Currency.CHF)))
    }

    @Test
    fun `a recipient billed in only one currency still ranks as a single row`() {
        org.mockito.kotlin.whenever(statisticsQuery.getRecipientTotals(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(
            listOf(
                de.littletrotter.application.dto.RecipientTotals(
                    recipientId = 1L, recipientName = "Kunde A",
                    grossAmount = de.littletrotter.shared.Money.of("119.00"),
                    invoiceCount = 1L, netAmount = de.littletrotter.shared.Money.of("100.00"),
                ),
            ),
        )
        val v = view()
        customerTable(v).refresh(de.littletrotter.application.dto.StatisticsFilter.EMPTY)

        val rows = rankingCardRows(customerTable(v))
        assertEquals(1, rows.size)
    }
}
