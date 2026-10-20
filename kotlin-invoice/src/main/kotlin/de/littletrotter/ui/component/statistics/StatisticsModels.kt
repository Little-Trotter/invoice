package de.littletrotter.ui.component.statistics

import com.vaadin.flow.component.icon.VaadinIcon
import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money

/** Pre-formatted year-over-year badge: [text] is shown as is, [positive] picks the colour. */
data class KpiDelta(val text: String, val positive: Boolean)

/** Colour family of a KPI tile; the stylesheet maps each tone to its theme colour (light and dark). */
enum class KpiTone(val cssClass: String) {
    PRIMARY("lt-tone--primary"),
    SUCCESS("lt-tone--success"),
    ERROR("lt-tone--error"),
    MUTED("lt-tone--muted"),
}

/** Icon and colour family a KPI tile is drawn with. */
data class KpiAccent(val icon: VaadinIcon, val tone: KpiTone)

/**
 * One KPI card. [value] is the headline figure; [extraValues] are further lines in the same
 * style (one per additional currency); [delta] and [caption] are optional sub-lines.
 */
data class KpiTile(
    val label: String,
    val value: String,
    val accent: KpiAccent,
    val extraValues: List<String> = emptyList(),
    val delta: KpiDelta? = null,
    val caption: String? = null,
)

/** One label/value line of a card; [bold] emphasises the value. */
data class LabeledValue(val label: String, val value: String, val bold: Boolean = false)

/**
 * One period card. [drillKey] makes the card clickable (reported back to the matrix's callback)
 * and is matched against the matrix's active key; [primary] draws the emphasised total look.
 * [barShare] is the percentage (0..100) of the proportional bar, `null` for no bar.
 */
data class PeriodCard(
    val label: String,
    val rows: List<LabeledValue>,
    val barShare: Int? = null,
    val drillKey: Int? = null,
    val primary: Boolean = false,
)

/** A row of period cards; [heading] captions the row when several groups (currencies) are shown. */
data class PeriodCardGroup(val heading: String?, val cards: List<PeriodCard>)

/** Colour family of a status card; the stylesheet maps each family to its background and border colours. */
enum class StatusColorFamily(val cssClass: String) {
    NEUTRAL("lt-tone--neutral"),
    PRIMARY("lt-tone--primary"),
    SUCCESS("lt-tone--success"),
    CANCELLATION("lt-tone--storno"),
    ERROR("lt-tone--error"),
    TOTAL("lt-tone--total"),
}

/** One status card: [count] with its pre-formatted [share] of the total. */
data class StatusCard(
    val label: String,
    val count: Long,
    val share: String,
    val colorFamily: StatusColorFamily,
    val icon: VaadinIcon,
)

/** A bar a click on which moves the page to [period], selecting [value] (quarter or month number). */
data class DrillTarget(val period: StatisticsPeriod, val value: Int)

/** One bar column: net and gross amounts plus the optional drill-down it triggers. */
data class TrendPoint(val label: String, val net: Money, val gross: Money, val drill: DrillTarget? = null)

/** One self-contained chart section; its axis is scaled to its own [points] and [currency]. */
data class TrendSeries(val currency: Currency, val points: List<TrendPoint>)

/**
 * Everything the trend chart draws; an empty [series] list renders the no-data hint. [showGross]
 * `false` draws net bars only, for figures that exist as net amounts alone.
 */
data class TrendModel(
    val title: String,
    val series: List<TrendSeries>,
    val onDrill: (DrillTarget) -> Unit,
    val showGross: Boolean = true,
)
