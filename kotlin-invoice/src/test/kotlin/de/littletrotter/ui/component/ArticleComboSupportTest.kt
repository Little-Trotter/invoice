package de.littletrotter.ui.component

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import de.littletrotter.application.dto.ArticleDto
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The one shared article-select combo presentation used by both `GoodsItemDialog` and
 * `ServiceItemDialog`: the "<number> · <name>" selected-value label, and the two-line suggestion
 * row (number column, name, muted description) with an em-dash for a missing number.
 */
class ArticleComboSupportTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun article(number: String? = "ART-2026-00036", name: String = "Kabelkanal", description: String? = "PVC, halogenfrei") =
        ArticleDto(articleNumber = number, name = name, description = description)

    // ── articleSelectedLabel ────────────────────────────────────────────────────────────────────

    @Test
    fun `selected label combines number and name with a middle dot`() {
        assertEquals("ART-2026-00036 · Kabelkanal", articleSelectedLabel(article()))
    }

    @Test
    fun `selected label falls back to the name alone when the number is null`() {
        assertEquals("Kabelkanal", articleSelectedLabel(article(number = null)))
    }

    // A blank-but-non-null article number is unrepresentable now (ArticleNumber's own
    // init rejects it at construction), so the former "falls back when blank" defensive presentation
    // test is obsolete; only the null case above is left to exercise.

    // ── articleSuggestionRow ────────────────────────────────────────────────────────────────────

    private fun header(row: VerticalLayout): HorizontalLayout =
        row.children.toList().filterIsInstance<HorizontalLayout>().first()

    private fun numberSpan(row: VerticalLayout): Span =
        header(row).children.toList().filterIsInstance<Span>().first()

    private fun nameSpan(row: VerticalLayout): Span =
        header(row).children.toList().filterIsInstance<Span>()[1]

    @Test
    fun `suggestion row shows the number, the name and the description on separate lines`() {
        val row = articleSuggestionRow(article()) { it }

        assertEquals("ART-2026-00036", numberSpan(row).text)
        assertEquals("Kabelkanal", nameSpan(row).text)
        val descriptionSpan = row.children.toList().filterIsInstance<Span>()
            .first { it.hasClassName("article-combo-description") }
        assertEquals("PVC, halogenfrei", descriptionSpan.text)
    }

    @Test
    fun `suggestion row shows an em-dash instead of an empty gap when the article carries no number`() {
        val row = articleSuggestionRow(article(number = null)) { it }

        assertEquals(ARTICLE_COMBO_EM_DASH, numberSpan(row).text)
    }

    @Test
    fun `suggestion row omits the description line entirely when the article carries none`() {
        val row = articleSuggestionRow(article(description = null)) { it }

        assertTrue(row.children.toList().filterIsInstance<Span>().none { it.hasClassName("article-combo-description") })
    }

    @Test
    fun `suggestion row's number column carries an aria-label from the caller's translation function`() {
        val row = articleSuggestionRow(article()) { key -> if (key == "article.articleNumber") "Artikelnummer" else key }

        assertEquals("Artikelnummer", numberSpan(row).element.getAttribute("aria-label"))
    }

    // ── applyArticleSelectPresentation ──────────────────────────────────────────────────────────

    @Test
    fun `applyArticleSelectPresentation wires the item label generator to articleSelectedLabel`() {
        val combo = ComboBox<ArticleDto>()

        combo.applyArticleSelectPresentation { it }

        assertEquals("ART-2026-00036 · Kabelkanal", combo.itemLabelGenerator.apply(article()))
    }
}
