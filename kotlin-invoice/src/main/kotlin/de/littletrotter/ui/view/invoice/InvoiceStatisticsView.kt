package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import com.vaadin.flow.component.tabs.TabSheet
import de.littletrotter.application.dto.StatisticsFilter
import de.littletrotter.application.invoice.port.inbound.InvoiceStatisticsQueryPort
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import de.littletrotter.ui.MainLayout
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.PageShell
import de.littletrotter.ui.component.RankingTable
import de.littletrotter.ui.component.statistics.DrillTarget
import de.littletrotter.ui.component.statistics.KpiRow
import de.littletrotter.ui.component.statistics.RevenueMatrix
import de.littletrotter.ui.component.statistics.StatisticsPeriod
import de.littletrotter.ui.component.statistics.StatusCardsRow
import de.littletrotter.ui.component.statistics.TrendChart
import de.littletrotter.ui.format.MoneyFormatter
import de.littletrotter.ui.i18n.TranslationProvider
import jakarta.inject.Inject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

private const val MONTHS_PER_YEAR = 12
private const val QUARTERS_PER_YEAR = 4
private const val SHARE_PERCENTAGE_SCALE = 4

/**
 * Filter state + orchestration only — each section is a self-contained component in
 * `ui/component/statistics`; an [InvoiceStatisticsPresenter] builds their models. This view builds
 * one [StatisticsFilter] from the current UI state per [refresh] and fans it out to every section.
 */
@Route(value = "statistics", layout = MainLayout::class)
@PageTitle("Rechnungsstatistik")
class InvoiceStatisticsView
@Inject
constructor(
    private val statisticsQuery: InvoiceStatisticsQueryPort,
    time: TimeSource,
) : VerticalLayout(), TranslationProvider {

    private val yearSelect = ComboBox<Int>()
    private val periodSelect = ComboBox<StatisticsPeriod>()
    private val monthSelect = ComboBox<Int>()
    private val quarterSelect = ComboBox<Int>()
    private val statusFilter = ComboBox<InvoiceStatus>()
    private val reverseChargeFilter = ComboBox<Boolean>()

    private var selectedYear: Int = time.today().year
    private var selectedMonth: Int? = null
    private var selectedQuarter: Int? = null
    private val breadcrumbs = HorizontalLayout()

    // Each section is a standalone component; this view only fans filter state out to them.
    private val presenter = InvoiceStatisticsPresenter(statisticsQuery, this)
    private val kpiRow = KpiRow()
    private val statusCardsRow = StatusCardsRow()
    private val revenueMatrix = RevenueMatrix { q ->
        periodSelect.value = StatisticsPeriod.QUARTER
        quarterSelect.value = q
    }
    private val trendChart = TrendChart()
    // getRecipientTotals/getSenderTotals/getArticleTotals now group
    // by currency too, so a recipient/sender/article billed in two currencies ranks as two rows,
    // each already carrying its own [de.littletrotter.shared.Currency]; every cell below is
    // formatted with that row's own currency rather than an assumed one.
    //
    // The "Anteil" percentage share is a pre-existing, separate concern: it already summed
    // grossAmount across every row in [all] regardless of currency before this fix, and still
    // does — a percentage carries no currency of its own to mislabel, unlike the Netto/Brutto
    // cells this fix addresses.
    private val customerTable = RankingTable(
        headers = listOf(
            tr("statistics.ranking.customer.customer"),
            tr("statistics.ranking.customer.net"),
            tr("statistics.ranking.customer.gross"),
            tr("statistics.ranking.customer.share"),
        ),
        rowMapper = { item, all ->
            val totalGross = all.sumOf { it.grossAmount.amount }
            val share = if (totalGross > BigDecimal.ZERO)
                item.grossAmount.amount.divide(totalGross, SHARE_PERCENTAGE_SCALE, RoundingMode.HALF_UP).multiply(BigDecimal(100))
            else BigDecimal.ZERO
            listOf(item.recipientName, formatCurrency(item.netAmount, item.currency), formatCurrency(item.grossAmount, item.currency), String.format(Locale.GERMAN, "%.1f%%", share))
        },
        fetch = { filter, limit -> statisticsQuery.getRecipientTotals(limit, filter) },
    )
    private val senderTable = RankingTable(
        headers = listOf(
            tr("statistics.ranking.provider.provider"),
            tr("statistics.ranking.provider.gross"),
            tr("statistics.ranking.provider.invoiceCount"),
        ),
        rowMapper = { item, _ -> listOf(item.senderName, formatCurrency(item.grossAmount, item.currency), item.invoiceCount.toString()) },
        fetch = { filter, limit -> statisticsQuery.getSenderTotals(limit, filter) },
    )
    private val positionTable = RankingTable(
        headers = listOf(
            tr("statistics.ranking.position.name"),
            tr("statistics.ranking.position.net"),
            tr("statistics.ranking.position.quantity"),
            tr("statistics.ranking.position.avgPrice"),
        ),
        rowMapper = { item, _ -> listOf(item.description, formatCurrency(item.netAmount, item.currency), item.itemCount.toString(), formatCurrency(item.avgPrice, item.currency)) },
        fetch = { filter, limit -> statisticsQuery.getArticleTotals(limit, filter) },
    )

    init {
        setSizeFull()
        isPadding = true
        isSpacing = true

        val availableYears = statisticsQuery.getAvailableYears()
        selectedYear = availableYears.maxOrNull() ?: time.today().year

        yearSelect.setItems(availableYears)
        yearSelect.value = selectedYear
        yearSelect.setItemLabelGenerator { it.toString() }
        yearSelect.addValueChangeListener { refresh() }

        periodSelect.setItems(*StatisticsPeriod.entries.toTypedArray())
        periodSelect.value = StatisticsPeriod.YEAR
        periodSelect.setItemLabelGenerator { tr(it.labelKey) }
        periodSelect.addValueChangeListener { onPeriodChanged() }

        monthSelect.setItems((1..MONTHS_PER_YEAR).toList())
        monthSelect.setItemLabelGenerator { Month.of(it).getDisplayName(TextStyle.FULL, Locale.GERMAN) }
        monthSelect.placeholder = tr("statistics.selectMonth")
        monthSelect.isVisible = false
        monthSelect.addValueChangeListener {
            selectedMonth = monthSelect.value
            refresh()
        }

        quarterSelect.setItems((1..QUARTERS_PER_YEAR).toList())
        quarterSelect.setItemLabelGenerator { quarterLabel(it) }
        quarterSelect.placeholder = tr("statistics.selectQuarter")
        quarterSelect.isVisible = false
        quarterSelect.addValueChangeListener {
            selectedQuarter = quarterSelect.value
            refresh()
        }

        statusFilter.placeholder = tr("invoice.table.status")
        statusFilter.setItems(*InvoiceStatus.entries.toTypedArray())
        statusFilter.setItemLabelGenerator { tr(it.labelKey) }
        statusFilter.isClearButtonVisible = true
        statusFilter.addValueChangeListener { refresh() }

        reverseChargeFilter.placeholder = tr("statistics.filter.reverseCharge")
        reverseChargeFilter.setItems(true, false)
        reverseChargeFilter.setItemLabelGenerator { tr(if (it) "statistics.filter.reverseCharge.yes" else "statistics.filter.reverseCharge.no") }
        reverseChargeFilter.isClearButtonVisible = true
        reverseChargeFilter.addValueChangeListener { refresh() }

        val refreshBtn = AppButtons.primary(tr("statistics.action.refresh"), ActionIcons.REFRESH) { refresh() }.apply {
            element.setAttribute("title", tr("statistics.action.refresh.tooltip"))
        }

        val filterBar = HorizontalLayout(yearSelect, periodSelect, monthSelect, quarterSelect, statusFilter, reverseChargeFilter).apply {
            defaultVerticalComponentAlignment = FlexComponent.Alignment.BASELINE
            isPadding = false
            addClassName("lt-stat-filter-bar")
            setWidthFull()
        }

        val tabSheet = TabSheet().apply {
            addClassNames("statistics-tabs")
            setWidthFull()
            add(tr("statistics.tab.trend"), trendChart)
            add(tr("statistics.tab.topCustomers"), customerTable)
            add(tr("statistics.tab.topProviders"), senderTable)
            add(tr("statistics.tab.topPositions"), positionTable)
        }

        val shell = PageShell(tr("statistics.pageTitle")).apply {
            actions(refreshBtn)
            filters(filterBar)
            content(
                buildSectionTitle(tr("statistics.section.kpis")),
                kpiRow,
                buildSectionTitle(tr("statistics.section.revenueOverview")),
                revenueMatrix,
                buildSectionTitle(tr("statistics.section.invoiceStatus")),
                statusCardsRow,
                statusCardsRow.contextLabel,
                buildSectionTitle(tr("statistics.section.trendAndRankings")),
                breadcrumbs,
                tabSheet,
            )
        }
        add(shell)
        refresh()
        updatePageTitle()
    }

    private fun updatePageTitle() {
        val ui = UI.getCurrent() ?: return
        ui.page.setTitle("${tr("statistics.pageTitle")} | ${tr("app.title")}")
    }

    // ─── Layout builders ────────────────────────────────────────────

    private fun buildSectionTitle(text: String): H2 = H2(text).apply {
        addClassName("lt-stat-section-title")
    }

    private fun onPeriodChanged() {
        selectedMonth = null
        selectedQuarter = null
        monthSelect.clear()
        quarterSelect.clear()
        when (periodSelect.value) {
            StatisticsPeriod.MONTH -> { monthSelect.isVisible = true; quarterSelect.isVisible = false }
            StatisticsPeriod.QUARTER -> { monthSelect.isVisible = false; quarterSelect.isVisible = true }
            else -> { monthSelect.isVisible = false; quarterSelect.isVisible = false }
        }
        refresh()
    }

    private fun onTrendDrill(target: DrillTarget) {
        periodSelect.value = target.period
        if (target.period == StatisticsPeriod.QUARTER) quarterSelect.value = target.value else monthSelect.value = target.value
    }

    override fun getComponent(): Component = this

    private fun formatCurrency(money: Money, currency: Currency): String = MoneyFormatter.format(money, currency)

    private fun quarterLabel(quarter: Int): String = tr("statistics.quarter.$quarter")

    // ─── Refresh — build one StatisticsFilter from UI state, fan it out ─────────

    /**
     * Single source of truth for "what does the UI currently ask for": senderId/recipientId/
     * filterText are always null (the statistics page has no controls for them); month/quarter
     * are only set when that granularity is the active [periodSelect] value, matching every
     * per-section query's original filter semantics (see each component's `refresh` KDoc for
     * which fields it actually honors).
     */
    private fun buildStatisticsFilter(): StatisticsFilter {
        val period = periodSelect.value ?: StatisticsPeriod.YEAR
        return StatisticsFilter(
            year = selectedYear,
            status = statusFilter.value,
            reverseCharge = reverseChargeFilter.value,
            month = if (period == StatisticsPeriod.MONTH) selectedMonth else null,
            quarter = if (period == StatisticsPeriod.QUARTER) selectedQuarter else null,
        )
    }

    private fun refresh() {
        val year = yearSelect.value ?: selectedYear
        selectedYear = year
        val period = periodSelect.value ?: StatisticsPeriod.YEAR
        updateBreadcrumbs()
        val filter = buildStatisticsFilter()
        kpiRow.render(presenter.kpiTiles(filter, period, year))
        statusCardsRow.render(presenter.statusCards(filter, year), presenter.statusContext(filter, period, year))
        revenueMatrix.render(presenter.quarterCards(filter, year), presenter.activeQuarter(filter, period))
        trendChart.render(presenter.trend(filter, year, period, ::onTrendDrill))
        customerTable.refresh(filter)
        senderTable.refresh(filter)
        positionTable.refresh(filter)
    }

    // ─── Breadcrumbs ────────────────────────────────────────────────

    private fun updateBreadcrumbs() {
        breadcrumbs.removeAll()
        breadcrumbs.isSpacing = true
        breadcrumbs.alignItems = FlexComponent.Alignment.CENTER
        breadcrumbs.addClassName("lt-stat-breadcrumbs")

        // A year is an identifier, not a quantity. Passing it as a String prevents
        // MessageFormat from applying the locale's thousands grouping (e.g. "2.026").
        val root = AppButtons.tertiary(tr("statistics.breadcrumb.year", selectedYear.toString())) {
            periodSelect.value = StatisticsPeriod.YEAR
            refresh()
        }.apply {
            removeThemeVariants(ButtonVariant.LUMO_TERTIARY)
            addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE)
        }
        breadcrumbs.add(root)

        if (periodSelect.value == StatisticsPeriod.QUARTER || periodSelect.value == StatisticsPeriod.MONTH) {
            breadcrumbs.add(Span(VaadinIcon.CHEVRON_RIGHT.create().apply { addClassName("lt-stat-chevron") }))
            val q = selectedQuarter
            if (q != null) {
                val qBtn = AppButtons.tertiary(quarterLabel(q)) {
                    periodSelect.value = StatisticsPeriod.QUARTER
                    quarterSelect.value = q
                    refresh()
                }.apply {
                    removeThemeVariants(ButtonVariant.LUMO_TERTIARY)
                    addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE)
                }
                breadcrumbs.add(qBtn)
            } else {
                breadcrumbs.add(Span(tr("statistics.breadcrumb.allQuarters")))
            }
        }

        if (periodSelect.value == StatisticsPeriod.MONTH) {
            breadcrumbs.add(Span(VaadinIcon.CHEVRON_RIGHT.create().apply { addClassName("lt-stat-chevron") }))
            val m = selectedMonth
            if (m != null) {
                breadcrumbs.add(Span(Month.of(m).getDisplayName(TextStyle.FULL, Locale.GERMAN)))
            } else {
                breadcrumbs.add(Span(tr("statistics.breadcrumb.allMonths")))
            }
        }
    }
}
