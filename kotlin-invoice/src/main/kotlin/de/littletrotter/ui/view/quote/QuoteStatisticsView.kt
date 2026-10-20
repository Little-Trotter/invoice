package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.tabs.TabSheet
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import de.littletrotter.application.quote.port.inbound.QuoteAvailabilityQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteDashboard
import de.littletrotter.application.quote.port.inbound.QuoteRankingKind
import de.littletrotter.application.quote.port.inbound.QuoteRankingQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteStatisticsDashboardQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteStatisticsSelection
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.port.inbound.AppRoleQueryPort
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.shared.time.TimeSource
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
import de.littletrotter.ui.i18n.TranslationProvider
import jakarta.inject.Inject
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

private const val MONTHS_PER_YEAR = 12
private const val QUARTERS_PER_YEAR = 4
private const val RANKING_LIMIT = 10

/**
 * Offer statistics laid out like the invoice statistics: filter bar, KPIs with the previous-year
 * comparison, volume by quarter with drill-down, status cards, and trend and rankings of the
 * sent offers. Filter state and orchestration only; each section is a shared component fed by a
 * [QuoteStatisticsPresenter]. The offer component computes every figure per request.
 *
 * Only reachable while the offer component is part of the instance and the caller may view offers.
 */
@Route(value = QuoteRoutes.STATISTICS, layout = MainLayout::class)
@PageTitle("Angebotsstatistik")
class QuoteStatisticsView
@Inject
constructor(
    private val dashboardQuery: QuoteStatisticsDashboardQueryPort,
    private val rankingQuery: QuoteRankingQueryPort,
    availability: QuoteAvailabilityQueryPort,
    roles: AppRoleQueryPort,
    time: TimeSource,
) : VerticalLayout(), BeforeEnterObserver, TranslationProvider {

    internal val yearSelect = ComboBox<Int>()
    internal val periodSelect = ComboBox<StatisticsPeriod>()
    internal val monthSelect = ComboBox<Int>()
    internal val quarterSelect = ComboBox<Int>()
    internal val statusFilter = ComboBox<QuoteStatus>()
    internal val customerTypeFilter = ComboBox<CustomerType>()
    internal val refreshButton = AppButtons.primary(t("action.refresh"), ActionIcons.REFRESH) { refresh() }
    internal val breadcrumbs = HorizontalLayout()
    internal val emptyState = Span(t("empty")).apply { addClassName("text-muted") }

    private val access = QuoteAccess(availability, roles)
    private val labels = QuoteLabels(this)
    private val presenter = QuoteStatisticsPresenter(this)

    internal val kpiRow = KpiRow()
    internal val revenueMatrix = RevenueMatrix { quarter ->
        periodSelect.value = StatisticsPeriod.QUARTER
        quarterSelect.value = quarter
    }
    internal val statusCardsRow = StatusCardsRow()
    internal val trendChart = TrendChart()
    internal val customerTable = RankingTable(
        headers = listOf(
            t("ranking.customer.name"),
            t("ranking.customer.net"),
            t("ranking.customer.count"),
            t("ranking.customer.share"),
        ),
        rowMapper = { item, all -> presenter.customerRow(item, all) },
    )
    internal val positionTable = RankingTable(
        headers = listOf(t("ranking.position.name"), t("ranking.position.net"), t("ranking.position.count")),
        rowMapper = { item, _ -> presenter.positionRow(item) },
    )

    private var selectedYear: Int = time.today().year
    private var selectedMonth: Int? = null
    private var selectedQuarter: Int? = null
    private var switching = false

    /** The quarter a month was drilled into from, so the breadcrumb can lead back to it. */
    private var parentQuarter: Int? = null

    override fun getComponent(): Component = this

    private fun t(key: String, vararg params: Any): String = tr("quote.statistics.$key", *params)

    init {
        setSizeFull()
        isPadding = true
        isSpacing = true
        configureFilters()
        add(
            PageShell(t("pageTitle")).apply {
                actions(refreshButton)
                filters(filterBar())
                content(
                    emptyState,
                    sectionTitle(t("section.kpis")),
                    kpiRow,
                    sectionTitle(t("section.volumeByQuarter")),
                    revenueMatrix,
                    sectionTitle(t("section.status")),
                    statusCardsRow,
                    statusCardsRow.contextLabel,
                    sectionTitle(t("section.trendAndRankings")),
                    breadcrumbs,
                    tabSheet(),
                )
            },
        )
        emptyState.isVisible = false
    }

    private fun configureFilters() {
        yearSelect.placeholder = t("selectYear")
        yearSelect.setItemLabelGenerator { it.toString() }
        yearSelect.addValueChangeListener { event ->
            if (!switching && event.value != null) {
                selectedYear = event.value
                refresh()
            }
        }

        periodSelect.setItems(*StatisticsPeriod.entries.toTypedArray())
        periodSelect.value = StatisticsPeriod.YEAR
        periodSelect.setItemLabelGenerator { t("period.${it.name.lowercase()}") }
        periodSelect.addValueChangeListener { onPeriodChanged() }

        monthSelect.setItems((1..MONTHS_PER_YEAR).toList())
        monthSelect.setItemLabelGenerator { monthName(it) }
        monthSelect.placeholder = t("selectMonth")
        monthSelect.isVisible = false
        monthSelect.addValueChangeListener {
            selectedMonth = monthSelect.value
            refresh()
        }

        quarterSelect.setItems((1..QUARTERS_PER_YEAR).toList())
        quarterSelect.setItemLabelGenerator { quarterLabel(it) }
        quarterSelect.placeholder = t("selectQuarter")
        quarterSelect.isVisible = false
        quarterSelect.addValueChangeListener {
            selectedQuarter = quarterSelect.value
            refresh()
        }

        statusFilter.placeholder = t("filter.status")
        statusFilter.setItems(*QuoteStatus.known.toTypedArray())
        statusFilter.setItemLabelGenerator { labels.status(it) }
        statusFilter.isClearButtonVisible = true
        statusFilter.addValueChangeListener { refresh() }

        customerTypeFilter.placeholder = t("filter.customerType")
        customerTypeFilter.setItems(*CustomerType.entries.toTypedArray())
        customerTypeFilter.setItemLabelGenerator { t("customerType.${it.name}") }
        customerTypeFilter.isClearButtonVisible = true
        customerTypeFilter.addValueChangeListener { refresh() }

        refreshButton.element.setAttribute("title", t("action.refresh.tooltip"))
    }

    private fun filterBar() = HorizontalLayout(
        yearSelect, periodSelect, monthSelect, quarterSelect, statusFilter, customerTypeFilter,
    ).apply {
        defaultVerticalComponentAlignment = FlexComponent.Alignment.BASELINE
        isPadding = false
        addClassName("lt-stat-filter-bar")
        setWidthFull()
    }

    private fun tabSheet() = TabSheet().apply {
        addClassNames("statistics-tabs")
        setWidthFull()
        add(t("tab.trend"), trendChart)
        add(t("tab.topCustomers"), customerTable)
        add(t("tab.topPositions"), positionTable)
    }

    private fun sectionTitle(text: String): H2 = H2(text).apply {
        addClassName("lt-stat-section-title")
    }

    override fun beforeEnter(event: BeforeEnterEvent) {
        if (!access.admit(event, AppPermission.QUOTE_VIEW)) return
        val first = guarded { dashboardQuery.dashboard(selection()) }
        if (first == null) {
            updateBreadcrumbs()
            return
        }
        val newest = first.availableYears.maxOrNull()
        if (newest != null && selectedYear !in first.availableYears) {
            selectedYear = newest
            refresh()
        } else {
            refresh(first)
        }
        UI.getCurrent().page.setTitle("${t("pageTitle")} | ${tr("app.title")}")
    }

    private fun onPeriodChanged() {
        selectedMonth = null
        selectedQuarter = null
        monthSelect.clear()
        quarterSelect.clear()
        val period = periodSelect.value
        if (period != StatisticsPeriod.MONTH) parentQuarter = null
        monthSelect.isVisible = period == StatisticsPeriod.MONTH
        quarterSelect.isVisible = period == StatisticsPeriod.QUARTER
        refresh()
    }

    private fun onTrendDrill(target: DrillTarget) {
        if (target.period == StatisticsPeriod.MONTH && period() == StatisticsPeriod.QUARTER) parentQuarter = selectedQuarter
        periodSelect.value = target.period
        if (target.period == StatisticsPeriod.QUARTER) quarterSelect.value = target.value else monthSelect.value = target.value
    }

    private fun period(): StatisticsPeriod = periodSelect.value ?: StatisticsPeriod.YEAR

    /** One selection from the current filter state; quarter and month only count in their own mode. */
    private fun selection(): QuoteStatisticsSelection {
        val period = period()
        return QuoteStatisticsSelection(
            year = selectedYear,
            quarter = if (period == StatisticsPeriod.QUARTER) selectedQuarter else null,
            month = if (period == StatisticsPeriod.MONTH) selectedMonth else null,
            status = statusFilter.value,
            customerType = customerTypeFilter.value,
        )
    }

    private fun refresh(prefetched: QuoteDashboard? = null) {
        updateBreadcrumbs()
        val selection = selection()
        (prefetched ?: guarded { dashboardQuery.dashboard(selection) })?.let { render(it, selection) }
        guarded { rankingQuery.ranking(QuoteRankingKind.CUSTOMER, selection, RANKING_LIMIT) }?.let { customerTable.render(it) }
        guarded { rankingQuery.ranking(QuoteRankingKind.ARTICLE, selection, RANKING_LIMIT) }?.let { positionTable.render(it) }
    }

    private fun render(dashboard: QuoteDashboard, selection: QuoteStatisticsSelection) {
        showYears(dashboard.availableYears)
        val period = period()
        emptyState.isVisible = dashboard.kpis.sentCount == 0 && dashboard.statusCounts.total == 0
        kpiRow.render(presenter.kpiTiles(dashboard))
        revenueMatrix.render(presenter.quarterCards(dashboard), presenter.activeQuarter(period, selection.quarter))
        statusCardsRow.render(
            presenter.statusCards(dashboard.statusCounts),
            presenter.statusContext(period, selection.month, selection.quarter, selection.year),
        )
        trendChart.render(
            presenter.trend(
                dashboard,
                period,
                QuoteStatisticsPresenter.PeriodSelection(selection.year, selection.quarter, selection.month),
                ::onTrendDrill,
            ),
        )
    }

    /** The years with offers plus the selected one, newest first, without reacting to the change. */
    private fun showYears(available: List<Int>) {
        switching = true
        try {
            yearSelect.setItems((available + selectedYear).distinct().sortedDescending())
            yearSelect.value = selectedYear
        } finally {
            switching = false
        }
    }

    private fun updateBreadcrumbs() {
        breadcrumbs.removeAll()
        breadcrumbs.isSpacing = true
        breadcrumbs.alignItems = FlexComponent.Alignment.CENTER
        breadcrumbs.addClassName("lt-stat-breadcrumbs")

        // A year is an identifier, not a quantity: passed as text so no thousands grouping applies.
        breadcrumbs.add(
            AppButtons.tertiary(t("breadcrumb.year", selectedYear.toString())) { periodSelect.value = StatisticsPeriod.YEAR }
                .apply { addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE) },
        )
        val period = period()
        if (period == StatisticsPeriod.QUARTER || period == StatisticsPeriod.MONTH) {
            breadcrumbs.add(chevron())
            val quarter = selectedQuarter ?: parentQuarter
            breadcrumbs.add(
                if (quarter != null) {
                    AppButtons.tertiary(quarterLabel(quarter)) {
                        periodSelect.value = StatisticsPeriod.QUARTER
                        quarterSelect.value = quarter
                    }.apply { addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE) }
                } else {
                    Span(t("breadcrumb.allQuarters"))
                },
            )
        }
        if (period == StatisticsPeriod.MONTH) {
            breadcrumbs.add(chevron())
            breadcrumbs.add(Span(selectedMonth?.let { monthName(it) } ?: t("breadcrumb.allMonths")))
        }
    }

    private fun chevron() = Span(VaadinIcon.CHEVRON_RIGHT.create().apply { addClassName("lt-stat-chevron") })

    private fun quarterLabel(quarter: Int): String = t("quarter.$quarter")

    private fun monthName(month: Int): String = Month.of(month).getDisplayName(TextStyle.FULL, Locale.GERMAN)
}
