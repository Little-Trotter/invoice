package de.littletrotter.ui.components.freetext

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span

/**
 * One clickable, currently-derivable insertion for [FreeTextChips]: [label] the chip's own short
 * caption, [fullName] the plain full name for the tooltip and screen readers, [insertText] what a
 * click appends to the free text. [group] separates unrelated candidate categories a future
 * layout might want to tell apart without forcing every [ChipSource] to agree on one flat order;
 * this component itself never reads it. [key] is an opaque id meaningless to this component too —
 * a specific [ChipSource] may hand it back through [FreeTextChips]'s click callback to recognise
 * its own candidate later (kotlin-invoice: the B2B term code, used to mark a term "already
 * imported" so the automatic silent merge never appends the same value again).
 */
data class ChipCandidate(
    val label: String,
    val fullName: String,
    val insertText: String,
    val group: String,
    val key: String? = null,
)

/**
 * Where [FreeTextChips] pulls its currently-filled candidates from. A host refreshes the row on
 * every change it considers relevant ([FreeTextChips.refresh]) instead of pushing pre-computed
 * rows in, so this component never needs to know what "relevant" means for that host — and a host
 * with several unrelated candidate sources (a B2B profile term, a plain form field, ...) composes
 * them into one [ChipSource] itself rather than this component understanding more than one shape.
 */
fun interface ChipSource {
    fun candidates(): List<ChipCandidate>
}

/**
 * The clickable chip row itself, rendered above [HtmlFreeTextEditor]. Purely presentational: rebuilt
 * from scratch on every [refresh] call (the candidate list is always small), with no chip at all —
 * and no reserved space — when [source] currently has nothing to offer.
 */
class FreeTextChips(private val source: ChipSource, private val onInsert: (ChipCandidate) -> Unit) : Div() {

    init {
        addClassName("lt-freetext-chips")
        isVisible = false
    }

    /** Re-reads [source] and rebuilds the row; a host calls this on every change to whatever it
     *  feeds [source] from, and once right after wiring so the row reflects the initial state. */
    fun refresh() {
        val candidates = source.candidates()
        removeAll()
        isVisible = candidates.isNotEmpty()
        candidates.forEach { candidate ->
            add(
                Button().apply {
                    addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY)
                    addClassName("lt-freetext-chip")
                    // Button.add is private, so the two caption parts go in via the element.
                    element.appendChild(
                        Span(candidate.label).apply { addClassName("lt-freetext-chip__label") }.element,
                        Span(candidate.insertText).apply { addClassName("lt-freetext-chip__value") }.element,
                    )
                    element.setAttribute(
                        "title",
                        FreeTextTexts.tr("freeText.chips.insert", candidate.fullName, candidate.insertText),
                    )
                    element.setAttribute("aria-label", "${candidate.fullName}: ${candidate.insertText}")
                    addClickListener { onInsert(candidate) }
                },
            )
        }
    }
}
