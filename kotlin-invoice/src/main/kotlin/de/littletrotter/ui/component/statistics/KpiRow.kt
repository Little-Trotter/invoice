package de.littletrotter.ui.component.statistics

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout

/**
 * Row of KPI cards drawn from a presentation model. The component knows nothing about where the
 * figures come from: a presenter hands over already formatted [KpiTile]s, one line per currency
 * for the money cards, so a period mixing currencies shows each sum under its own code instead of
 * one falsely labelled total.
 */
class KpiRow : HorizontalLayout() {

    init {
        setWidthFull(); isPadding = false; isSpacing = false
        // The stylesheet turns the row into an even grid (one column per card) and falls back to
        // wrapping columns on a narrow viewport instead of letting the cards overflow.
        addClassName("lt-kpi-row")
    }

    /** Replaces all cards by [tiles]. */
    fun render(tiles: List<KpiTile>) {
        removeAll()
        // The stylesheet lays the tiles out as one even row of this many columns on wide viewports.
        style.set("--lt-kpi-cols", tiles.size.coerceAtLeast(1).toString())
        tiles.forEach { add(kpiCard(it)) }
    }

    private fun kpiCard(tile: KpiTile): Div = Div().apply {
        addClassNames("lt-kpi-card", tile.accent.tone.cssClass)

        val head = HorizontalLayout(tile.accent.icon.create().apply { addClassName("lt-kpi-card__icon") },
            Span(tile.label).apply { addClassName("lt-kpi-card__label") }).apply {
            alignItems = FlexComponent.Alignment.CENTER
            isPadding = false; isSpacing = false
            addClassName("lt-kpi-card__head")
        }
        val value = Span(tile.value).apply { addClassName("lt-kpi-card__value") }

        val children = mutableListOf<Component>(head, value)
        tile.delta?.let { children += deltaBadge(it) }
        if (tile.extraValues.isNotEmpty()) children += extraLines(tile.extraValues)
        tile.caption?.let { children += captionSpan(it) }
        add(*children.toTypedArray())
    }

    private fun deltaBadge(delta: KpiDelta): Span = Span(delta.text).apply {
        addClassNames("lt-kpi-card__delta", if (delta.positive) "lt-kpi-card__delta--up" else "lt-kpi-card__delta--down")
    }

    private fun extraLines(values: List<String>): Div = Div().apply {
        addClassName("lt-kpi-card__extra")
        values.forEach { add(Span(it)) }
    }

    private fun captionSpan(text: String): Span = Span(text).apply { addClassName("lt-kpi-card__caption") }
}
