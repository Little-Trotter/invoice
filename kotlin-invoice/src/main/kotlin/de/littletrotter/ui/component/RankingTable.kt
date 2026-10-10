package de.littletrotter.ui.component

import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import de.littletrotter.application.dto.StatisticsFilter
import de.littletrotter.ui.i18n.UiTranslations
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Generic top-N ranking list, shared by the statistics page's Top-Kunden/Top-Anbieter/
 * Top-Artikel tabs. Renders a lightweight caption row (column labels) above a vertical stack of
 * ranking cards, one per item — replaces the former flat-row table; the top-ranked item's rank
 * badge now carries the "who's #1" signal that used to be zebra striping.
 *
 * [headers] has 3 or 4 entries (name column + 2–3 value columns). [rowMapper] renders one item
 * into that many display strings; it receives the full result list too, so a column that depends
 * on the whole page (e.g. a percentage share of the total) can be computed without a second
 * query. [fetch] performs the query itself, given the page's [StatisticsFilter] and a page size.
 */
class RankingTable<T>(
    private val headers: List<String>,
    private val rowMapper: (item: T, all: List<T>) -> List<String>,
    private val fetch: (filter: StatisticsFilter, limit: Int) -> List<T> = { _, _ -> emptyList() },
) : Div() {

    init {
        addClassName("ranking-table")
    }

    fun refresh(filter: StatisticsFilter, limit: Int = 10) {
        render(fetch(filter, limit))
    }

    /** Draws [items] as they are, for a page that fetched the ranking itself. */
    fun render(items: List<T>) {
        removeAll()
        if (items.isEmpty()) {
            add(Span(UiTranslations.tr(this, "statistics.noData")))
            return
        }

        val has4Cols = headers.size >= FOUR_COLUMN_COUNT
        val rowValues = items.map { rowMapper(it, items) }
        val topValue = magnitude(rowValues.first()[1])

        add(captionRow(has4Cols))
        add(Div().apply {
            addClassName("ranking-card-stack")
            rowValues.forEachIndexed { index, values -> add(card(index, values, has4Cols, topValue)) }
        })
    }

    /** Small uppercase label row carrying the column headers above the card stack — not a card
     *  itself, so the columns stay legible without repeating labels on every card. */
    private fun captionRow(has4Cols: Boolean): Div = Div().apply {
        addClassName("ranking-caption-row")
        add(Span().apply { addClassName("ranking-caption-rank") })
        add(Span(headers[0]).apply {
            addClassNames("ranking-caption-label", "ranking-truncate")
        })
        add(captionValue(headers[1]))
        add(captionValue(headers[2]))
        if (has4Cols) add(captionValue(headers[FOURTH_COLUMN_INDEX]))
    }

    private fun captionValue(text: String): Span = Span(text).apply {
        addClassNames("ranking-caption-label", "ranking-caption-value")
    }

    /** One ranking card: a rank badge + name + 2–3 value columns on top, a proportional bar
     *  underneath. [index] `0` (the top-ranked item) gets a filled rank badge instead of the
     *  outlined one every other rank gets — the redesign's replacement for the old zebra
     *  striping as the way a reader's eye finds "who's #1" at a glance. */
    private fun card(index: Int, values: List<String>, has4Cols: Boolean, topValue: BigDecimal): Div {
        val isTop = index == 0
        val row = Div().apply {
            addClassName("ranking-card-row")
            add(rankBadge(index, isTop))
            add(nameCell(values[0]))
            add(valueCell(values[1], "ranking-card-value--strong"))
            add(valueCell(values[2]))
            if (has4Cols && values.size >= FOUR_COLUMN_COUNT) {
                add(valueCell(values[FOURTH_COLUMN_INDEX], "ranking-card-value--strong", "ranking-card-value--accent"))
            }
        }
        return Div().apply {
            addClassName("ranking-card")
            if (isTop) addClassName("ranking-card--top")
            add(row, proportionalBar(magnitude(values[1]), topValue))
        }
    }

    private fun rankBadge(index: Int, isTop: Boolean): Span = Span("#${index + 1}").apply {
        addClassName("ranking-card-rank")
        if (isTop) addClassName("ranking-card-rank--top")
    }

    private fun nameCell(text: String): Span = Span(text).apply {
        addClassNames("ranking-card-name", "ranking-truncate")
    }

    private fun valueCell(text: String, vararg modifiers: String): Span = Span(text).apply {
        addClassName("ranking-card-value")
        modifiers.forEach { addClassName(it) }
    }

    /** Gradient fill bar under a card, proportional to [value] against the top-ranked item's
     *  [topValue] — [topValue] `<= 0` (every ranking value is zero) renders a zero-width bar
     *  instead of dividing by zero. Same gradient-`Div` idiom as `TrendChart.buildColumn`'s bars. */
    private fun proportionalBar(value: BigDecimal, topValue: BigDecimal): Div = Div().apply {
        addClassName("ranking-card-bar-track")
        val percent = if (topValue <= BigDecimal.ZERO) {
            0
        } else {
            value.coerceAtLeast(BigDecimal.ZERO).multiply(BigDecimal(PERCENT_SCALE)).divide(topValue, 0, RoundingMode.HALF_UP)
                .coerceIn(BigDecimal.ZERO, BigDecimal(PERCENT_SCALE)).toInt()
        }
        add(Div().apply {
            addClassName("ranking-card-bar-fill")
            style.set("--lt-bar-w", "$percent%")
        })
    }

    /** Best-effort numeric magnitude of a display string produced by [rowMapper] (a German-
     *  formatted `MoneyFormatter` amount like `"1.234,56 EUR"`, or a plain integer like `"5"`) —
     *  used only to size the proportional bar, never for arithmetic that must be exact. Anything
     *  that does not parse (an empty/unexpected string) contributes zero rather than crashing. */
    private fun magnitude(text: String): BigDecimal {
        val cleaned = text.filter { it.isDigit() || it == ',' || it == '.' || it == '-' }
        val normalized = if (cleaned.contains(',')) cleaned.replace(".", "").replace(',', '.') else cleaned
        return normalized.toBigDecimalOrNull() ?: BigDecimal.ZERO
    }

    private companion object {
        const val PERCENT_SCALE = 100
        const val FOUR_COLUMN_COUNT = 4
        const val FOURTH_COLUMN_INDEX = 3
    }
}
