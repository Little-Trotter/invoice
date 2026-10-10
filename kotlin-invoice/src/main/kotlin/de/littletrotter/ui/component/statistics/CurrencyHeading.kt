package de.littletrotter.ui.component.statistics

import com.vaadin.flow.component.html.Span

/**
 * The small caption a per-currency section prints above its own grid or chart body once a
 * period's data spans more than one currency; shared by the revenue matrix and the trend chart.
 */
internal fun currencyHeading(text: String): Span = Span(text).apply { addClassName("lt-currency-heading") }
