package de.littletrotter.ui.component

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span

/**
 * Building blocks of the read-only overview pages (invoice and offer): a party is shown as a card
 * with its name as lead, a compact address block and a grid of labelled fields. A field without a
 * value is not rendered at all, so the page never shows rows of dashes.
 */

/** Cards side by side, one column on a narrow screen. */
internal fun overviewCards(vararg sections: Component): Div =
    Div(*sections).apply { addClassName("overview-cards") }

/** The party name, set large above the address. */
internal fun overviewLead(text: String): Span = Span(text).apply { addClassName("overview-field__lead") }

/** One line per address row; `null` when every line is blank, so no empty block takes up space. */
internal fun overviewAddress(lines: List<String>): Div? {
    val rows = lines.filter { it.isNotBlank() }
    if (rows.isEmpty()) return null
    return Div(*rows.map { Div(Span(it)) }.toTypedArray()).apply { addClassName("overview-field__address") }
}

/** Container for the labelled fields of one card. */
internal fun overviewFieldGrid(): Div = Div().apply { addClassName("overview-fields") }

/** A labelled text value; `null` when the value is blank. A wide field spans the whole grid row. */
internal fun overviewField(label: String, value: String?, wide: Boolean = false): Div? {
    if (value.isNullOrBlank()) return null
    return overviewLabeled(label, Span(value).apply { addClassName("cockpit-field-value") }, wide)
}

/** A labelled arbitrary component, e.g. a link. */
internal fun overviewLabeled(label: String, value: Component, wide: Boolean = false): Div =
    Div(Span(label).apply { addClassName("cockpit-field-label") }, value).apply {
        addClassName("cockpit-field")
        if (wide) addClassName("overview-field--wide")
    }

/** Street and "postal code city, country" as two address rows; blank parts are dropped. */
internal fun addressLines(street: String?, postalCode: String?, city: String?, country: String?): List<String> {
    val postal = listOfNotNull(
        listOfNotNull(postalCode, city).filter { it.isNotBlank() }.joinToString(" ").ifEmpty { null },
        country?.takeIf { it.isNotBlank() },
    ).joinToString(", ")
    return listOfNotNull(street?.takeIf { it.isNotBlank() }, postal.ifEmpty { null })
}
