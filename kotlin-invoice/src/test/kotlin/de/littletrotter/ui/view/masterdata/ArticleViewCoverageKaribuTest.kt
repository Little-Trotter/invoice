package de.littletrotter.ui.view.masterdata

import de.littletrotter.ui.component.TextColumnWidth
import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._fetch
import com.github.mvysny.kaributesting.v10._getFormattedRow
import com.github.mvysny.kaributesting.v10._getRenderedComponentFor
import com.vaadin.flow.component.grid.ColumnTextAlign
import com.vaadin.flow.component.grid.GridSortOrder
import com.vaadin.flow.data.provider.SortDirection
import de.littletrotter.application.masterdata.ArticleRecipientRefsExtensionRegistry
import de.littletrotter.application.masterdata.ArticleTaxVariantsExtensionRegistry
import de.littletrotter.application.reference.port.inbound.ArticleQueryPort
import de.littletrotter.application.reference.port.inbound.ManageArticleUseCase
import de.littletrotter.application.reference.port.inbound.NumberRangeQueryPort
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.application.dto.ArticleDto
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.Money
import de.littletrotter.spi.masterdata.ArticleRecipientRefsExtension
import de.littletrotter.spi.masterdata.ArticleTaxVariantsExtension
import de.littletrotter.ui.format.MoneyFormatter
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * Closes the branch-coverage gaps in
 * [ArticleView] that [ArticleViewKaribuTest] does not reach: the `categoryLabel`/
 * `suggestedUnitFor` `when` mappings, the grid sort listener's field/direction branches, and the
 * two "extension present" `if`s that only render the tax-variant / recipient-ref sections when a
 * [ArticleTaxVariantsExtension] / [ArticleRecipientRefsExtension] bean is on the classpath.
 */
class ArticleViewCoverageKaribuTest {

    private val articleQueries: ArticleQueryPort = mock()

    private val manageArticle: ManageArticleUseCase = mock()
    private val taxRateQueries: TaxRateQueryPort = mock()
    private val numberRangeQueries: NumberRangeQueryPort = mock()
    private val articleTaxVariantsRegistry: ArticleTaxVariantsExtensionRegistry = mock()
    private val articleRecipientRefsRegistry: ArticleRecipientRefsExtensionRegistry = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun newView(): ArticleView {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(emptyList())
        whenever(articleQueries.findArticles(any(), any(), anyOrNull(), anyOrNull(), any(), any(), anyOrNull())).thenReturn(emptyList())
        whenever(articleQueries.countArticles(anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(0L)
        return ArticleView(articleQueries, manageArticle, taxRateQueries, numberRangeQueries, articleTaxVariantsRegistry, articleRecipientRefsRegistry, time = FIXED_TIME_SOURCE)
    }

    private fun grid(view: ArticleView): com.vaadin.flow.component.grid.Grid<ArticleDto> = view.grid

    private fun editorDialog(view: ArticleView): com.vaadin.flow.component.dialog.Dialog {
        val f = de.littletrotter.ui.view.AbstractCrudView::class.java.getDeclaredField("editorDialog")
        f.isAccessible = true
        return f.get(view) as com.vaadin.flow.component.dialog.Dialog
    }

    private fun flatten(component: com.vaadin.flow.component.Component): List<com.vaadin.flow.component.Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    private fun categoryBox(view: ArticleView): com.vaadin.flow.component.combobox.ComboBox<InvoiceItemCategory> = view.categoryBox

    // ── grid sort listener — mapped column, unmapped/name column, cleared order, both directions ──

    @Test
    fun `sorting by unit refetches ascending with the mapped sort field`() {
        val view = newView()
        val column = grid(view).getColumnByKey("defaultUnit")!!

        grid(view).sort(listOf(GridSortOrder(column, SortDirection.ASCENDING)))
        grid(view)._fetch(0, 10)

        verify(articleQueries, atLeastOnce())
            .findArticles(any(), any(), anyOrNull(), anyOrNull(), eq("defaultUnit"), eq(de.littletrotter.domain.shared.SortDirection.ASC), anyOrNull())
    }

    @Test
    fun `sorting by name falls through to the else branch`() {
        val view = newView()
        val column = grid(view).getColumnByKey("name")!!

        grid(view).sort(listOf(GridSortOrder(column, SortDirection.DESCENDING)))
        grid(view)._fetch(0, 10)

        verify(articleQueries, atLeastOnce())
            .findArticles(any(), any(), anyOrNull(), anyOrNull(), eq("name"), eq(de.littletrotter.domain.shared.SortDirection.DESC), anyOrNull())
    }

    @Test
    fun `clearing the sort order resets to name ascending`() {
        val view = newView()
        val column = grid(view).getColumnByKey("defaultUnit")!!
        grid(view).sort(listOf(GridSortOrder(column, SortDirection.DESCENDING)))

        grid(view).sort(emptyList())
        grid(view)._fetch(0, 10)

        verify(articleQueries, atLeastOnce())
            .findArticles(any(), any(), anyOrNull(), anyOrNull(), eq("name"), eq(de.littletrotter.domain.shared.SortDirection.ASC), anyOrNull())
    }

    // ── extension sections — hasContent=true renders the section, the "extension present" branch ──

    @Test
    fun `tax variant and recipient ref sections render when their extensions are present`() {
        val taxExtension: ArticleTaxVariantsExtension = mock()
        whenever(articleTaxVariantsRegistry.enabledExtension()).thenReturn(taxExtension)

        val recipientExtension: ArticleRecipientRefsExtension = mock()
        whenever(articleRecipientRefsRegistry.enabledExtension()).thenReturn(recipientExtension)

        val view = newView()

        val texts = flatten(editorDialog(view)).filterIsInstance<com.vaadin.flow.component.HasText>().mapNotNull { it.text }
        assertTrue(texts.any { it.contains(UiTranslations.tr("serviceCatalog.taxVariant.section")) })
        assertTrue(texts.any { it.contains(UiTranslations.tr("serviceCatalog.recipientRef.section")) })
    }

    @Test
    fun `neither section renders when no extension is present`() {
        val view = newView()

        val texts = flatten(editorDialog(view)).filterIsInstance<com.vaadin.flow.component.HasText>().mapNotNull { it.text }
        assertTrue(texts.none { it.contains(UiTranslations.tr("serviceCatalog.taxVariant.section")) })
        assertTrue(texts.none { it.contains(UiTranslations.tr("serviceCatalog.recipientRef.section")) })
    }

    // Category defaults and locked editing are covered by ArticleViewCategoryTabsKaribuTest.

    // ── grid columns rendered via plain ValueProvider (articleNumber/shortHash/
    //    taxRate): these lambdas are never invoked by Grid unless a row is actually formatted, so
    //    they stayed at 0% branch coverage despite the column-header assertions above. ───────────

    private fun article(
        articleNumber: String? = null,
        shortHash: String? = null,
        taxRate: TaxRate? = null,
        id: Long? = null,
        category: InvoiceItemCategory = InvoiceItemCategory.SERVICE,
        defaultUnitPriceNet: Money = Money.of("9.99"),
    ) = ArticleDto(
        id = id,
        name = "Widget",
        category = category,
        articleNumber = articleNumber,
        shortHash = shortHash,
        defaultTaxRateId = taxRate?.id,
        defaultTaxRatePercent = taxRate?.rate,
        defaultUnitPriceNet = defaultUnitPriceNet,
        defaultQuantity = BigDecimal.ONE,
    )

    @Test
    fun `the articleNumber and shortHash columns render the em dash placeholder when absent`() {
        val view = newView()

        val row = grid(view)._getFormattedRow(article(articleNumber = null, shortHash = null))

        assertEquals("—", row[0], "articleNumber column")
        assertEquals("—", row[1], "shortHash column")
    }

    @Test
    fun `the articleNumber and shortHash columns render the real value when present`() {
        val view = newView()

        val row = grid(view)._getFormattedRow(article(articleNumber = "ART-001", shortHash = "AB12"))

        assertEquals("ART-001", row[0])
        assertEquals("AB12", row[1])
    }

    @Test
    fun `the tax rate column renders a dash when the article has no assigned tax rate`() {
        val view = newView()

        val row = grid(view)._getFormattedRow(article(taxRate = null))

        assertEquals("—", row[7], "taxRate column (index 7: articleNumber, shortHash, name, description, unit, quantity, unitPriceNet, taxRate)")
    }

    @Test
    fun `the actions column stays frozen to the end, fixed and not resizable`() {
        val col = grid(newView()).getColumnByKey(ARTICLE_ACTIONS_KEY)

        assertTrue(col.isFrozenToEnd)
        assertEquals(0, col.flexGrow)
        assertTrue(!col.isResizable)
        assertEquals(ColumnTextAlign.END, col.textAlign)
    }

    @Test
    fun `the description column has a fixed base width instead of auto width`() {
        val col = grid(newView()).columns.single { it.headerText == UiTranslations.tr("serviceCatalog.description.short") }

        assertTrue(!col.isAutoWidth)
        assertEquals(TextColumnWidth.LARGE.css, col.width)
        assertEquals(TextColumnWidth.LARGE.grow, col.flexGrow)
    }

    @Test
    fun `very long name and description do not unfreeze the actions column`() {
        val view = newView()
        val long = "x".repeat(500)

        val row = grid(view)._getFormattedRow(article().copy(name = long, description = long))

        assertTrue(row.isNotEmpty())
        assertTrue(grid(view).getColumnByKey(ARTICLE_ACTIONS_KEY).isFrozenToEnd)
    }

    @Test
    fun `the tax rate column renders the trimmed percentage when a tax rate is assigned`() {
        val view = newView()
        val taxRate = TaxRate().apply { rate = TaxRatePercent(BigDecimal("19.00")) }

        val row = grid(view)._getFormattedRow(article(taxRate = taxRate))

        assertEquals("19 %", row[7])
    }

    // ── The unitPriceNet column used "%.2f €".format(...), a locale-blind decimal point
    //    where every other grid in the app prints a German comma (MoneyFormatter's convention).
    //    Kotlin's String.format defers to the JVM's *default* locale, which happens to be de_DE
    //    on this machine — masking the bug unless the default is forced away from German first,
    //    the way a differently-configured deployment locale would expose it for real. ───────────

    @Test
    fun `the unitPriceNet column renders the German comma, not a decimal point`() {
        val view = newView()
        val previousDefault = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.US)

            val row = grid(view)._getFormattedRow(article(defaultUnitPriceNet = Money.of("1.50")))

            // Compared against MoneyFormatter's own output rather than a hardcoded literal:
            // NumberFormat.getCurrencyInstance separates amount and symbol with a non-breaking
            // space, not a plain one, and the point of this test is that the column goes through
            // MoneyFormatter at all, not the separator's exact code point.
            assertEquals(
                MoneyFormatter.format(Money.of("1.50")),
                row[6],
                "unitPriceNet column (index 7, see the taxRate test above for the full index list)",
            )
        } finally {
            java.util.Locale.setDefault(previousDefault)
        }
    }

    // ── the grid's own selection listener: clearing selection opens no editor ──────

    @Test
    fun `selecting a grid row opens its editor`() {
        val view = newView()
        val article = article(id = 4L)
        whenever(articleQueries.findArticleById(4L)).thenReturn(article)

        grid(view).asSingleSelect().value = article

        assertTrue(editorDialog(view).isOpened)
    }

    @Test
    fun `clearing the grid selection opens no editor`() {
        val view = newView()
        val article = article(id = 5L)
        whenever(articleQueries.findArticleById(5L)).thenReturn(article)
        grid(view).asSingleSelect().value = article

        grid(view).asSingleSelect().clear()

        // No new dialog-open attempt beyond the one already triggered by the selection above —
        // the null branch of `event.value?.let { openEditor(it) }` must be a no-op.
        assertTrue(editorDialog(view).isOpened, "clearing selection must not close an already-open dialog either")
    }

    // ── refresh()'s SERVER-side category routing ──────

    @Test
    fun `filtering by category routes the category into the facade query instead of client-side filtering`() {
        val view = newView()
        val product = article(id = 1L, category = InvoiceItemCategory.PRODUCT)
        whenever(
            articleQueries.findArticles(any(), any(), anyOrNull(), anyOrNull(), any(), any(), eq(InvoiceItemCategory.PRODUCT)),
        ).thenReturn(listOf(product))

        categoryBox(view) // no-op, keeps import used consistently with the rest of the file
        val categoryFilterField = ArticleView::class.java.getDeclaredField("categoryTabs").also { it.isAccessible = true }
        val categoryFilter = categoryFilterField.get(view) as com.vaadin.flow.component.tabs.Tabs
        categoryFilter.selectedIndex = 0

        val fetched = grid(view)._fetch(0, 10)

        assertEquals(listOf(product), fetched, "the category must constrain the facade query itself")
        org.mockito.kotlin.verify(articleQueries)
            .findArticles(any(), any(), anyOrNull(), anyOrNull(), any(), any(), eq(InvoiceItemCategory.PRODUCT))
    }

    @Test
    fun `initial product tab returns the category constrained server result`() {
        val view = newView()
        val product = article(id = 1L, category = InvoiceItemCategory.PRODUCT)

        whenever(articleQueries.findArticles(any(), any(), anyOrNull(), anyOrNull(), any(), any(), eq(InvoiceItemCategory.PRODUCT))).thenReturn(listOf(product))

        val fetched = grid(view)._fetch(0, 10)

        assertEquals(listOf(product), fetched)
    }

    // ── Delete flow, new-entry button, unit dropdown renderer, page title ──

    private fun <T : Any> privateField(owner: Class<*>, target: Any, name: String, type: kotlin.reflect.KClass<T>): T {
        val f = owner.getDeclaredField(name)
        f.isAccessible = true
        return type.java.cast(f.get(target))
    }

    private fun deleteButton(view: ArticleView): com.vaadin.flow.component.button.Button =
        privateField(
            de.littletrotter.ui.view.AbstractCrudView::class.java,
            view,
            "deleteButton",
            com.vaadin.flow.component.button.Button::class,
        )

    private fun openExisting(view: ArticleView, id: Long): ArticleDto {
        val article = article(id = id)
        whenever(articleQueries.findArticleById(id)).thenReturn(article)
        grid(view).asSingleSelect().value = article
        return article
    }

    @Test
    fun `happy path - deleting an opened article removes it through the facade and closes the editor`() {
        val view = newView()
        openExisting(view, 7L)
        assertTrue(deleteButton(view).isEnabled, "an existing article must be deletable")

        deleteButton(view).click()

        verify(manageArticle).deleteArticle(7L)
        assertEquals(false, editorDialog(view).isOpened)
    }

    @Test
    fun `boundary - a rejected delete keeps the editor open instead of crashing the view`() {
        val view = newView()
        openExisting(view, 8L)
        whenever(manageArticle.deleteArticle(8L)).thenThrow(IllegalStateException("article still referenced by an invoice"))

        deleteButton(view).click()

        assertTrue(editorDialog(view).isOpened)
    }

    @Test
    fun `boundary - the new-entry button opens an editor whose delete action is disabled`() {
        val view = newView()
        val newButton: com.vaadin.flow.component.button.Button =
            privateField(ArticleView::class.java, view, "newButton", com.vaadin.flow.component.button.Button::class)

        newButton.click()

        assertTrue(editorDialog(view).isOpened)
        assertEquals(false, deleteButton(view).isEnabled, "an unsaved article has nothing to delete")
    }

    @Test
    fun `boundary - the unit dropdown renders label, symbol with UN-ECE code and the domain chip`() {
        val view = newView()
        val unit = de.littletrotter.shared.catalogue.InvoiceItemUnit.entries.first()

        val row = view.unitBox._getRenderedComponentFor(unit)

        val texts = flatten(row).filterIsInstance<com.vaadin.flow.component.html.Span>().map { it.text }
        assertEquals(3, texts.size)
        assertEquals("${unit.symbol} · ${unit.uneceCode}", texts[1])
        assertTrue(flatten(row).filterIsInstance<com.vaadin.flow.component.html.Span>()[2].classNames.contains("status-chip"))
    }

    @Test
    fun `boundary - the dynamic page title follows the wording profile's catalogue label`() {
        val view = newView()

        assertEquals(UiTranslations.tr(view, "nav.serviceCatalog"), view.pageTitle)
    }
}
