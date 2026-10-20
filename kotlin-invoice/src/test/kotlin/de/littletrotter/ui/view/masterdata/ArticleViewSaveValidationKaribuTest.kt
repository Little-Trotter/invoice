package de.littletrotter.ui.view.masterdata

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._findAll
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.textfield.BigDecimalField
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.masterdata.ArticleRecipientRefsExtensionRegistry
import de.littletrotter.application.masterdata.ArticleTaxVariantsExtensionRegistry
import de.littletrotter.application.reference.port.inbound.ArticleQueryPort
import de.littletrotter.application.reference.port.inbound.ManageArticleUseCase
import de.littletrotter.application.reference.port.inbound.NumberRangeQueryPort
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.application.dto.ArticleDto
import de.littletrotter.ui.view.AbstractCrudView
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import de.littletrotter.ui.view.referencedata.ArticleFormData
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import java.math.BigDecimal
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * [ArticleView.writeValidatedForm] (and the quantity/price binder validators it
 * drives, and the descriptionArea getter/setter's blank-handling branches) had no dedicated test:
 * every existing fixture only ever exercises the all-valid path indirectly through
 * `readFormData`/`applyFormData`. Closes lines 327 (description getter/setter), 335/338
 * (quantity/price `v == null || v >= ZERO` validators) and 340/341 (price's Money converter).
 */
class ArticleViewSaveValidationKaribuTest {

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

    private fun textField(view: ArticleView, name: String): TextField {
        val f = ArticleView::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(view) as TextField
    }

    private fun textArea(view: ArticleView, name: String): TextArea {
        val f = ArticleView::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(view) as TextArea
    }

    private fun bigDecimalField(view: ArticleView, name: String): BigDecimalField {
        val f = ArticleView::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(view) as BigDecimalField
    }

    private fun categoryBox(view: ArticleView): ComboBox<InvoiceItemCategory> = view.categoryBox

    private fun unitBox(view: ArticleView): ComboBox<InvoiceItemUnit> = view.unitBox

    private fun writeValidatedForm(view: ArticleView, formData: ArticleFormData): Boolean {
        val m = ArticleView::class.java.getDeclaredMethod("writeValidatedForm", ArticleFormData::class.java)
        m.isAccessible = true
        return m.invoke(view, formData) as Boolean
    }

    private fun readFormData(view: ArticleView, formData: ArticleFormData) {
        val m = ArticleView::class.java.getDeclaredMethod("readFormData", ArticleFormData::class.java)
        m.isAccessible = true
        m.invoke(view, formData)
    }

    private fun fillValidForm(view: ArticleView) {
        textField(view, "nameField").value = "Widget"
        categoryBox(view).value = InvoiceItemCategory.SERVICE
        unitBox(view).value = InvoiceItemUnit.HOUR
        bigDecimalField(view, "quantityField").value = BigDecimal.ONE
        bigDecimalField(view, "priceField").value = BigDecimal("9.99")
    }

    // ── quantityField / priceField validators: `v == null || v >= BigDecimal.ZERO` ─────────────

    @Test
    fun `writeValidatedForm succeeds with a valid non-negative quantity and price`() {
        val view = newView()
        fillValidForm(view)

        assertTrue(writeValidatedForm(view, ArticleFormData()))
    }

    @Test
    fun `saving an old article dialog rejects a newer loaded revision before mutation`() {
        val view = newView()
        val opened = ArticleDto(id = 5L, name = "Opened", version = 7L)
        whenever(articleQueries.findArticleById(5L)).thenReturn(opened)
        AbstractCrudView::class.java.getDeclaredMethod("openEditor", Any::class.java)
            .also { it.isAccessible = true }.invoke(view, opened)
        fillValidForm(view)
        val latest = ArticleDto(id = 5L, name = "Concurrent edit", version = 8L)
        whenever(articleQueries.findArticleById(5L)).thenReturn(latest)

        (AbstractCrudView::class.java.getDeclaredField("saveButton")
            .also { it.isAccessible = true }.get(view) as Button).click()

        verify(manageArticle, never()).saveArticle(any())
        assertEquals("Concurrent edit", latest.name)
        assertEquals(8L, latest.version)
        assertTrue((AbstractCrudView::class.java.getDeclaredField("editorDialog")
            .also { it.isAccessible = true }.get(view) as Dialog).isOpened)
    }

    @Test
    fun `writeValidatedForm succeeds when quantity is left null`() {
        val view = newView()
        fillValidForm(view)
        bigDecimalField(view, "quantityField").value = null

        assertTrue(writeValidatedForm(view, ArticleFormData()))
    }

    @Test
    fun `writeValidatedForm fails for a negative quantity`() {
        val view = newView()
        fillValidForm(view)
        bigDecimalField(view, "quantityField").value = BigDecimal("-1")

        assertFalse(writeValidatedForm(view, ArticleFormData()))
    }

    @Test
    fun `writeValidatedForm succeeds when price is left null`() {
        val view = newView()
        fillValidForm(view)
        bigDecimalField(view, "priceField").value = null

        assertTrue(writeValidatedForm(view, ArticleFormData()))
    }

    @Test
    fun `writeValidatedForm fails for a negative price`() {
        val view = newView()
        fillValidForm(view)
        bigDecimalField(view, "priceField").value = BigDecimal("-5")

        assertFalse(writeValidatedForm(view, ArticleFormData()))
    }

    // ── priceField's Money converter (lines 340/341): amount present vs. absent, both directions ─

    @Test
    fun `a filled price converts to a Money of that amount on write`() {
        val view = newView()
        fillValidForm(view)
        bigDecimalField(view, "priceField").value = BigDecimal("12.50")
        val formData = ArticleFormData()

        writeValidatedForm(view, formData)

        assertEquals(BigDecimal("12.50"), formData.defaultUnitPriceNet?.amount)
    }

    @Test
    fun `a null price converts to Money ZERO on write`() {
        val view = newView()
        fillValidForm(view)
        bigDecimalField(view, "priceField").value = null
        val formData = ArticleFormData()

        writeValidatedForm(view, formData)

        assertEquals(BigDecimal.ZERO.stripTrailingZeros(), formData.defaultUnitPriceNet?.amount?.stripTrailingZeros())
    }

    // ── descriptionArea binder (line 327): getter null->"" / non-null, setter blank->null / kept ─

    @Test
    fun `readFormData shows a blank description area when the loaded description is null`() {
        val view = newView()

        readFormData(view, ArticleFormData(name = "Widget", description = null))

        assertEquals("", textArea(view, "descriptionArea").value)
    }

    @Test
    fun `readFormData shows the loaded description text when present`() {
        val view = newView()

        readFormData(view, ArticleFormData(name = "Widget", description = "Ein Text"))

        assertEquals("Ein Text", textArea(view, "descriptionArea").value)
    }

    @Test
    fun `writeValidatedForm normalises a blank description area to null`() {
        val view = newView()
        fillValidForm(view)
        textArea(view, "descriptionArea").value = "   "
        val formData = ArticleFormData()

        writeValidatedForm(view, formData)

        assertNull(formData.description)
    }

    @Test
    fun `writeValidatedForm keeps a non-blank description area as-is`() {
        val view = newView()
        fillValidForm(view)
        textArea(view, "descriptionArea").value = "Beschreibung"
        val formData = ArticleFormData()

        writeValidatedForm(view, formData)

        assertEquals("Beschreibung", formData.description)
    }

    // ── description grid column (line 159): null vs. blank both take the em-dash branch ─────────

    private fun descriptionCell(view: ArticleView, article: ArticleDto): com.vaadin.flow.component.html.Span {
        val column = view.grid.columns.first { it.headerText == de.littletrotter.ui.i18n.UiTranslations.tr("serviceCatalog.description.short") }
        return column.renderer.javaClass.getMethod("createComponent", Any::class.java)
            .invoke(column.renderer, article) as com.vaadin.flow.component.html.Span
    }

    @Test
    fun `the description column renders the em dash when the description is null`() {
        val view = newView()
        val article = ArticleDto(id = 1L, name = "Widget", description = null)

        val cell = descriptionCell(view, article)

        assertEquals("—", cell.text)
    }

    @Test
    fun `the description column renders the em dash when the description is blank`() {
        val view = newView()
        val article = ArticleDto(id = 1L, name = "Widget", description = "   ")

        val cell = descriptionCell(view, article)

        assertEquals("—", cell.text)
    }

    // ── refresh()'s filter normalisation (line 470): a typed, non-blank filter reaches the facade ──

    private fun grid(view: ArticleView): com.vaadin.flow.component.grid.Grid<ArticleDto> = view.grid

    @Test
    fun `a typed filter value reaches the facade as a non-null trimmed string`() {
        val view = newView()

        textField(view, "filterField").value = "  Widget  "
        grid(view)._findAll()

        org.mockito.kotlin.verify(articleQueries, org.mockito.kotlin.atLeastOnce())
            .findArticles(any(), any(), anyOrNull(), org.mockito.kotlin.eq("Widget"), any(), any(), anyOrNull())
    }
}
