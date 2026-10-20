package de.littletrotter.ui.component.statistics

import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span

/**
 * Period quick-overview drawn from a presentation model: one info card per period plus an
 * optional emphasised total card, grouped in rows that carry a caption when several currencies
 * are shown. Clicking a card with a drill key reports it through [onCardSelected]; the card whose
 * key equals the active key given to [render] is highlighted.
 */
class RevenueMatrix(
    private val onCardSelected: (Int) -> Unit,
) : Div() {

    init {
        addClassName("revenue-matrix")
    }

    /** Replaces all rows by [groups]; [active] is the drill key of the highlighted card. */
    fun render(groups: List<PeriodCardGroup>, active: Int?) {
        removeAll()
        groups.forEach { group ->
            group.heading?.let { add(currencyHeading(it)) }
            add(buildCardRow(group.cards, active))
        }
    }

    private fun buildCardRow(cards: List<PeriodCard>, active: Int?): Div {
        val row = Div().apply {
            addClassName("revenue-card-row")
        }
        cards.forEach { row.add(card(it, active != null && it.drillKey == active)) }
        return row
    }

    private fun card(card: PeriodCard, isActive: Boolean): Div {
        val key = card.drillKey
        return cardBase(isActive).apply {
            addClassName(if (card.primary) "revenue-card--total" else "revenue-card--quarter")
            if (isActive) addClassName("revenue-card--active")
            if (key != null) {
                addClassName("revenue-card--clickable")
                addClickListener { onCardSelected(key) }
            }
            add(cardLabel(card.label, primary = isActive || card.primary))
            add(valuesBlock(card.rows, card.primary))
            card.barShare?.let { add(proportionalBar(it)) }
        }
    }

    /**
     * Shared "info card" chrome — rounded corners, subtle border/shadow, hover lift — for both
     * period and total cards; [isActive] additionally applies the drilled-into highlight.
     */
    private fun cardBase(isActive: Boolean): Div = Div().apply {
        addClassName("revenue-card")
        if (isActive) addClassName("revenue-card--active")
    }

    private fun cardLabel(text: String, primary: Boolean): Span = Span(text).apply {
        addClassName("revenue-card__label")
        if (primary) addClassName("revenue-card__label--primary")
    }

    private fun valuesBlock(rows: List<LabeledValue>, primary: Boolean): Div = Div().apply {
        addClassName("revenue-card__values")
        rows.forEach { add(valueRow(it.label, it.value, bold = it.bold, primary = primary)) }
    }

    private fun valueRow(label: String, value: String, bold: Boolean, primary: Boolean): Div = Div().apply {
        addClassName("revenue-card__row")
        add(Span(label).apply { addClassName("revenue-card__row-label") })
        add(Span(value).apply {
            addClassName("revenue-card__row-value")
            if (bold) addClassName("revenue-card__row-value--bold")
            if (primary) addClassName("revenue-card__row-value--primary")
        })
    }

    /**
     * Small proportional bar under a card; the fill is a CSS percentage of the fixed-height
     * track, so no pixel math is needed for the animation itself.
     */
    private fun proportionalBar(percent: Int): Div = Div().apply {
        addClassName("revenue-card__bar-track")
        add(Div().apply {
            addClassName("revenue-card__bar-fill")
            style.set("--lt-bar-h", "${percent.coerceIn(0, MAX_PERCENT)}%")
        })
    }

    private companion object {
        const val MAX_PERCENT = 100
    }
}
