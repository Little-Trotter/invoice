package de.littletrotter.ui.component.statistics

import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import de.littletrotter.ui.format.MoneyFormatter
import de.littletrotter.ui.i18n.UiTranslations
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Net/gross bar chart drawn from a [TrendModel]. Clicking a bar that carries a [DrillTarget]
 * reports it through the model's `onDrill`; the view owns the filter controls and reacts the same
 * way as when a user picks the period directly.
 *
 * Every series is a self-contained chart section scaled to its own axis (a CHF bar next to a EUR
 * bar sharing one axis would misrepresent both). A single series renders unlabeled; a currency
 * caption only appears once a second series shows up.
 */
class TrendChart : Div() {

    /** Whether the gross bar and legend entry are drawn; set per [render] from the model. */
    private var showGross = true

    init {
        // Daily views can render 31 columns at a fixed minimum width each; the card scrolls
        // horizontally (see the stylesheet) instead of letting the bars overflow the page.
        addClassName("lt-trend-chart")
    }

    /** Replaces the chart by the sections of [model], or a no-data hint when it has none. */
    fun render(model: TrendModel) {
        removeAll()
        showGross = model.showGross
        if (model.series.isEmpty()) { add(noDataSpan()); return }

        add(chartHeader(model.title))

        val series = model.series.sortedBy { it.currency.ordinal }
        series.forEach { section ->
            if (series.size > 1) add(currencyHeading(section.currency.code))
            add(buildChartBody(section.points, section.currency, model.onDrill))
        }
    }

    // ── chrome shared across every currency section ─────────────────────────────────────────

    private fun chartHeader(titleText: String): HorizontalLayout = HorizontalLayout().apply {
        setWidthFull()
        alignItems = FlexComponent.Alignment.BASELINE
        val title = Span(titleText).apply { addClassName("lt-trend-chart__title") }
        add(title)

        val spacer = Div().apply { addClassName("lt-trend-chart__spacer") }
        add(spacer)

        val legend = HorizontalLayout().apply {
            isSpacing = true
            add(legendDot("lt-trend-chart__dot--net", "Netto"))
            if (showGross) add(legendDot("lt-trend-chart__dot--gross", "Brutto"))
        }
        add(legend)
    }

    /** One chart section (y-axis + bars) for a single currency's already gap-filled [points]. */
    private fun buildChartBody(points: List<TrendPoint>, currency: Currency, onDrill: (DrillTarget) -> Unit): Div {
        val maxVal = points.maxOfOrNull { maxOf(it.net, it.gross) } ?: Money.ZERO
        val displayMax = if (maxVal <= Money.ZERO) Money.of(BigDecimal(FALLBACK_AXIS_MAX)) else maxVal

        val chartArea = Div().apply { addClassName("lt-trend-chart__area") }
        val scale = ChartScale(displayMax, currency)
        points.forEach { pt -> chartArea.add(buildColumn(pt, points.size, scale, onDrill)) }

        return Div().apply {
            addClassName("lt-trend-chart__body")
            add(buildYAxis(displayMax, currency), chartArea)
        }
    }

    /**
     * Y-axis scale labels (0%, 25%, 50%, 75%, 100% of [displayMax]) — the axis spans the
     * plot height, while [buildChartBody]'s bars scale against [CHART_HEIGHT_PX], since percentage
     * heights on bar divs are relative to their flex parent, which has no resolved height at
     * Vaadin server-side render time.
     */
    private fun buildYAxis(displayMax: Money, currency: Currency): Div = Div().apply {
        addClassName("lt-trend-chart__axis")
        AXIS_PERCENT_STEPS.forEach { pct ->
            val amount = displayMax.amount.multiply(BigDecimal(pct)).divide(BigDecimal(AXIS_PERCENT_SCALE), 0, RoundingMode.HALF_UP)
            add(Span(formatCurrency(Money.of(amount), currency)).apply { addClassName("lt-trend-chart__axis-label") })
        }
    }

    /** [displayMax]/[currency] stay constant across a whole chart's columns — bundled so
     *  [buildColumn] takes one parameter for them instead of two (LongParameterList). */
    private data class ChartScale(val displayMax: Money, val currency: Currency)

    /** One bar column — net+gross bars, their value label and the period label underneath, wired
     *  to the drill callback when [TrendPoint.drill] says this point can be drilled into further. */
    private fun buildColumn(pt: TrendPoint, columnCount: Int, scale: ChartScale, onDrill: (DrillTarget) -> Unit): VerticalLayout {
        val (displayMax, currency) = scale
        val dense = columnCount > DENSE_COLUMN_THRESHOLD
        // No `displayMax > ZERO` guard: displayMax is the FALLBACK_AXIS_MAX fallback whenever
        // maxVal <= ZERO (see buildChartBody), so it is positive by construction and the guard's
        // else side was unreachable. Dividing by it is always safe.
        val netPx = barHeightPx(pt.net, displayMax)
        val grossPx = barHeightPx(pt.gross, displayMax)

        return VerticalLayout().apply {
            isPadding = false; isSpacing = false
            addClassName("lt-trend-chart__column")
            if (dense) addClassName("lt-trend-chart__column--dense")
            if (pt.drill != null) addClassName("lt-trend-chart__column--drillable")
            alignItems = FlexComponent.Alignment.CENTER
            justifyContentMode = FlexComponent.JustifyContentMode.END

            wireDrillClick(this, pt, onDrill)

            add(buildValueLabel(pt, currency), buildBars(BarHeights(netPx, grossPx), pt, currency), buildPeriodLabel(pt, columnCount))
        }
    }

    /** Wires the click-to-drill behavior onto [column] (the hover highlight is a stylesheet rule) — a no-op for a point
     *  that cannot be drilled into further ([TrendPoint.drill] `null`). */
    private fun wireDrillClick(column: VerticalLayout, pt: TrendPoint, onDrill: (DrillTarget) -> Unit) {
        val target = pt.drill ?: return
        column.addClickListener { onDrill(target) }
    }

    /** Headline value label above the bars (gross, or net when no gross is drawn); blank when zero. */
    private fun buildValueLabel(pt: TrendPoint, currency: Currency): Span {
        val shown = if (showGross) pt.gross else pt.net
        return Span(if (shown > Money.ZERO) formatCurrency(shown, currency) else "").apply { addClassName("lt-trend-chart__value") }
    }

    /** [net]/[gross] pixel heights bundled so [buildBars] takes one parameter for them instead
     *  of two (LongParameterList). */
    private data class BarHeights(val net: Int, val gross: Int)

    /** Net/gross bars side by side; the pixel height is the only data-driven style. */
    private fun buildBars(heights: BarHeights, pt: TrendPoint, currency: Currency): HorizontalLayout =
        HorizontalLayout().apply {
            addClassName("lt-trend-chart__bars")
            alignItems = FlexComponent.Alignment.END
            isPadding = false; isSpacing = false
            add(
                Div().apply {
                    addClassNames("lt-trend-chart__bar", "lt-trend-chart__bar--net")
                    style.set("--lt-bar-h", "${heights.net}px")
                    element.setAttribute("title", "${pt.label}: Netto ${formatCurrency(pt.net, currency)}")
                },
            )
            if (!showGross) return@apply
            add(
                Div().apply {
                    addClassNames("lt-trend-chart__bar", "lt-trend-chart__bar--gross")
                    style.set("--lt-bar-h", "${heights.gross}px")
                    element.setAttribute("title", "${pt.label}: Brutto ${formatCurrency(pt.gross, currency)}")
                },
            )
        }

    private fun buildPeriodLabel(pt: TrendPoint, columnCount: Int): Span =
        Span(pt.label).apply {
            addClassName("lt-trend-chart__period")
            if (columnCount > VERTICAL_LABEL_THRESHOLD) addClassName("lt-trend-chart__period--vertical")
        }

    /** One bar's pixel height: [amount] scaled against [displayMax] over [CHART_HEIGHT_PX],
     *  floored at a visible sliver ([MIN_VISIBLE_BAR_PX]) whenever [amount] is positive so a tiny
     *  real value never renders as a literal 0px (indistinguishable from "no data"). */
    private fun barHeightPx(amount: Money, displayMax: Money): Int =
        amount.amount.divide(displayMax.amount, DIVISION_SCALE, RoundingMode.HALF_UP)
            .multiply(BigDecimal(CHART_HEIGHT_PX)).toInt().coerceAtLeast(if (amount > Money.ZERO) MIN_VISIBLE_BAR_PX else 0)

    private fun legendDot(dotClass: String, text: String): HorizontalLayout = HorizontalLayout(
        Span().apply { addClassNames("lt-trend-chart__dot", dotClass) },
        Span(text).apply { addClassName("lt-trend-chart__legend-text") },
    ).apply { isPadding = false; isSpacing = false; alignItems = FlexComponent.Alignment.CENTER }

    private fun noDataSpan(): Span = Span(UiTranslations.tr(this, "statistics.noData")).apply { addClassName("lt-trend-chart__empty") }

    private fun formatCurrency(money: Money, currency: Currency): String = MoneyFormatter.format(money, currency)

    private companion object {
        /** Pixel height the tallest bar scales to. It stays below the stylesheet's `--lt-chart-h`
         *  (260px) so the value label above the tallest bar and the period label below fit inside
         *  the plot instead of running into the chart title. Absolute pixels because percentage
         *  heights on bar divs are relative to a flex parent without a resolved height at Vaadin
         *  server-side render time. */
        const val CHART_HEIGHT_PX = 200

        /** Y-axis fallback scale when every point in a section is zero — an empty chart with a
         *  0-height axis would read as broken, not as "no revenue yet". */
        const val FALLBACK_AXIS_MAX = 100

        val AXIS_PERCENT_STEPS = listOf(0, 25, 50, 75, 100)
        const val AXIS_PERCENT_SCALE = 100

        const val DIVISION_SCALE = 4

        /** Above this many columns, bars/columns switch to their denser (narrower) sizing. */
        const val DENSE_COLUMN_THRESHOLD = 20

        /** Above this many columns, the period label rotates vertical to keep fitting. */
        const val VERTICAL_LABEL_THRESHOLD = 15

        /** The smallest a positive bar is ever allowed to shrink to — below this it would be
         *  visually indistinguishable from a genuine zero. */
        const val MIN_VISIBLE_BAR_PX = 2
    }
}
