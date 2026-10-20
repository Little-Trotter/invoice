package de.littletrotter.ui.component.statistics

import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout

/**
 * Row of status cards plus a context label ("Angezeigt für: …") for the period shown, drawn from
 * a presentation model. Which statuses exist and which colour family each uses is decided by the
 * presenter, so the same row serves any life cycle.
 */
class StatusCardsRow : HorizontalLayout() {

    /** Public so the view can place it under this row. */
    val contextLabel = Span().apply { addClassName("lt-status-cards__context") }

    init {
        setWidthFull(); isPadding = false; isSpacing = false
        // HorizontalLayout defaults to nowrap; the stylesheet wraps the cards instead of letting
        // them overflow the row on a narrow viewport.
        addClassName("lt-status-cards")
    }

    /** Replaces all cards by [cards] and sets the [context] caption. */
    fun render(cards: List<StatusCard>, context: String) {
        removeAll()
        cards.forEach { add(statusCard(it)) }
        contextLabel.text = context
    }

    private fun statusCard(card: StatusCard): Div = Div().apply {
        addClassNames("lt-status-card", card.colorFamily.cssClass)

        val iconRow = HorizontalLayout(
            card.icon.create().apply { addClassName("lt-status-card__icon") },
            Span(card.label).apply { addClassName("lt-status-card__label") },
        ).apply {
            alignItems = FlexComponent.Alignment.CENTER; isPadding = false; isSpacing = false
            addClassName("lt-status-card__head")
        }
        val countSpan = Span(card.count.toString()).apply { addClassName("lt-status-card__count") }
        val shareSpan = Span(card.share).apply { addClassName("lt-status-card__share") }
        add(iconRow, countSpan, shareSpan)
    }
}
