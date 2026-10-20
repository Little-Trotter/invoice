package de.littletrotter.ui.component

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.data.renderer.ComponentRenderer
import de.littletrotter.application.dto.ArticleDto

/**
 * The article-select combos' (GoodsItemDialog/ServiceItemDialog) shared suggestion-row
 * renderer and selected-value label. One implementation applied to both dialogs' combos instead of
 * two copies, so the number column, the em-dash for a missing number, and the `number · name`
 * selected label cannot drift apart between them.
 */
internal const val ARTICLE_COMBO_EM_DASH = "—"

/** Applies the two-line suggestion row and the `"<number> · <name>"` selected-value label to
 *  combo. [tr] resolves an i18n key the same way every item dialog already does (a plain
 *  lambda instead of a `Component` reference, since the row is built off-tree by the renderer). */
internal fun ComboBox<ArticleDto>.applyArticleSelectPresentation(tr: (String) -> String) {
    setRenderer(ComponentRenderer { article -> articleSuggestionRow(article, tr) })
    setItemLabelGenerator { articleSelectedLabel(it) }
}

/** `"<articleNumber> · <name>"`, or just `name` when the article carries no number. */
internal fun articleSelectedLabel(article: ArticleDto): String {
    val number = article.articleNumber
    return if (number != null) "$number · ${article.name}" else article.name
}

/** The two-line suggestion row itself, `internal` (not `private`) so ArticleComboSupportTest
 *  can assert its shape directly instead of reaching through Vaadin's own `Renderer` plumbing,
 *  which exposes no public getter to read a previously-set renderer back. */
internal fun articleSuggestionRow(article: ArticleDto, tr: (String) -> String): VerticalLayout {
    val numberText = article.articleNumber ?: ARTICLE_COMBO_EM_DASH
    val number = Span(numberText).also {
        it.addClassName("article-combo-number")
        it.element.setAttribute("aria-label", tr("article.articleNumber"))
    }
    val name = Span(article.name).also { it.addClassName("article-combo-name") }
    val header = HorizontalLayout(number, name).also {
        it.isPadding = false
        it.isSpacing = true
        it.addClassName("article-combo-header")
    }

    val description = article.description?.takeIf { it.isNotBlank() }
    val descriptionSpan = description?.let { Span(it).also { span -> span.addClassName("article-combo-description") } }
    val children = listOfNotNull<Component>(header, descriptionSpan)
    return VerticalLayout(*children.toTypedArray()).also {
        it.addClassName("article-combo-suggestion")
        it.isPadding = false
        it.isSpacing = false
    }
}
