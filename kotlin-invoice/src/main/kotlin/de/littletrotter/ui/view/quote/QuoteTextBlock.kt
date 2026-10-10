package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.Html
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import de.littletrotter.shared.documents.FreeTextMarkup
import de.littletrotter.ui.components.freetext.ChipCandidate
import de.littletrotter.ui.components.freetext.ChipSource
import de.littletrotter.ui.components.freetext.FreeTextChips
import de.littletrotter.ui.components.freetext.HtmlFreeTextEditor

/**
 * One formatted free text of an offer: a captioned row of chips that insert known values at the
 * caret, directly above the rich-text editor, and a preview rendered from the stored markup, i.e. exactly what the document will show.
 */
internal class QuoteTextBlock(label: String, placeholdersCaption: String, candidates: () -> List<ChipCandidate>) : Div() {

    internal val editor = HtmlFreeTextEditor(label, FreeTextMarkup.MAX_LENGTH)
    internal val chips = FreeTextChips(ChipSource(candidates)) { editor.insertAtCursor(it.insertText) }
    private val caption = Span(placeholdersCaption).apply { addClassName("quote-placeholder-caption") }
    private val placeholderRow = Div(caption, chips).apply { addClassName("quote-placeholder-row") }
    private val preview = Div().apply { addClassName("quote-text-preview") }

    /** Canonical free-text markup; empty when nothing is entered. */
    var markup: String
        get() = editor.value
        set(value) {
            editor.value = value
            renderPreview()
        }

    init {
        addClassName("lt-freetext-block")
        editor.addValueChangeListener { renderPreview() }
        add(placeholderRow, editor, preview)
        placeholderRow.isVisible = false
        renderPreview()
    }

    /** The caption belongs to the chips, so the whole row disappears when there is nothing to insert. */
    fun refreshChips() {
        chips.refresh()
        placeholderRow.isVisible = chips.isVisible
    }

    private fun renderPreview() {
        preview.removeAll()
        if (editor.value.isNotEmpty()) preview.add(Html(FreeTextMarkup.toHtml(editor.value)))
    }
}
