package de.littletrotter.ui.component

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon

private val COUNT_SPLIT = Regex("""^(\S*\d\S*)\s+(.+)$""")

/**
 * Fills the band's count with the number as a bold span and the word as a secondary one, by
 * splitting the already formatted count string at its first digit-bearing token, so the plural
 * handling stays in the single translation key. A text without a number stays one word span.
 */
internal fun Span.showJournalCount(text: String) {
    removeAll()
    val match = COUNT_SPLIT.matchEntire(text)
    if (match == null) {
        add(Span(text).apply { addClassName("journal-band__count-word") })
    } else {
        add(
            Span(match.groupValues[1]).apply { addClassName("journal-band__count-num") },
            Span(" " + match.groupValues[2]).apply { addClassName("journal-band__count-word") },
        )
    }
    element.setAttribute("aria-label", text)
}

/** Archive symbol and [title] on the left, then the [trailing] parts (count, actions) on the right. */
internal fun journalBand(title: Component, vararg trailing: Component): Div = Div().apply {
    addClassName("journal-band")
    add(
        Div().apply {
            addClassName("journal-band__heading")
            add(
                VaadinIcon.ARCHIVE.create().apply {
                    addClassName("journal-band__icon")
                    element.setAttribute("aria-hidden", "true")
                },
                title,
            )
        },
        *trailing,
    )
}
