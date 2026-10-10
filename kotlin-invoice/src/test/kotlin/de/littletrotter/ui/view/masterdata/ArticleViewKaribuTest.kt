package de.littletrotter.ui.view.masterdata

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.router.Route
import de.littletrotter.shared.Money
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.ui.navigation.NavItem
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import com.github.mvysny.kaributesting.v10._fetch
import com.github.mvysny.kaributesting.v10._size
import de.littletrotter.application.masterdata.ArticleRecipientRefsExtensionRegistry
import de.littletrotter.application.masterdata.ArticleTaxVariantsExtensionRegistry
import de.littletrotter.application.reference.port.inbound.ArticleQueryPort
import de.littletrotter.application.reference.port.inbound.ManageArticleUseCase
import de.littletrotter.application.reference.port.inbound.NumberRangeQueryPort
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.application.dto.ArticleDraft
import de.littletrotter.application.dto.ArticleDto
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.reference.NumberCollisionException
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.domain.value.TaxRatePercent
import java.math.BigDecimal
import de.littletrotter.test.FIXED_TIME_SOURCE

class ArticleViewKaribuTest {

    private val articleQueries: ArticleQueryPort = mock()

    private val manageArticle: ManageArticleUseCase = mock()
    private val taxRateQueries: TaxRateQueryPort = mock()
    private val numberRangeQueries: NumberRangeQueryPort = mock()
    // No extension bean present (mock defaults enabledExtension to null).
    private val articleTaxVariantsRegistry: ArticleTaxVariantsExtensionRegistry = mock()
    // Same no-extension-bean default.
    private val articleRecipientRefsRegistry: ArticleRecipientRefsExtensionRegistry = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @Test
    fun `ArticleView route is articles and nav-menu entry resolves to it`() {
        val routeAnnotation = ArticleView::class.java.getAnnotation(Route::class.java)
        assertEquals("articles", routeAnnotation.value, "ArticleView @Route must be 'articles'")
        assertEquals(NavItem.ARTICLES, NavItem.activeItemFor("articles"), "nav-menu entry must resolve to /articles")
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    @Test
    fun `grid data provider invokes facade`() {
        val sample = ArticleDto(
            id = 7L,
            name = "Widget",
            description = "A widget",
            defaultUnitPriceNet = Money.of("9.99"),
            defaultQuantity = BigDecimal("1"),
            defaultTaxRatePercent = TaxRatePercent(BigDecimal("19")),
        )

        whenever(taxRateQueries.findAllTaxRates()).thenReturn(listOf<TaxRateDto>())
        whenever(articleQueries.findArticles(0, 50, null, null, "name", SortDirection.ASC, InvoiceItemCategory.PRODUCT)).thenReturn(listOf(sample))
        whenever(articleQueries.countArticles(null, null, InvoiceItemCategory.PRODUCT)).thenReturn(1L)

        val view = ArticleView(articleQueries, manageArticle, taxRateQueries, numberRangeQueries, articleTaxVariantsRegistry, articleRecipientRefsRegistry, time = FIXED_TIME_SOURCE)
        val grid = view.grid
        grid._fetch(0, 50)
        grid._size()

        verify(articleQueries).findArticles(0, 50, null, null, "name", SortDirection.ASC, InvoiceItemCategory.PRODUCT)
        verify(articleQueries).countArticles(null, null, InvoiceItemCategory.PRODUCT)
    }

    private fun field(view: ArticleView, name: String): TextField {
        val f = ArticleView::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(view) as TextField
    }

    private fun readFormData(view: ArticleView, formData: de.littletrotter.ui.view.referencedata.ArticleFormData) {
        val m = ArticleView::class.java.getDeclaredMethod("readFormData", de.littletrotter.ui.view.referencedata.ArticleFormData::class.java)
        m.isAccessible = true
        m.invoke(view, formData)
    }

    private fun persistForm(view: ArticleView, formData: de.littletrotter.ui.view.referencedata.ArticleFormData, current: ArticleDto?) {
        val m = ArticleView::class.java.getDeclaredMethod("persistForm", de.littletrotter.ui.view.referencedata.ArticleFormData::class.java, ArticleDto::class.java)
        m.isAccessible = true
        m.invoke(view, formData, current)
    }

    /** Saves the form through the view and returns the draft it handed to the use case. */
    private fun savedDraft(view: ArticleView, formData: de.littletrotter.ui.view.referencedata.ArticleFormData, current: ArticleDto? = null): ArticleDraft {
        persistForm(view, formData, current)
        return argumentCaptor<ArticleDraft>().also { verify(manageArticle).saveArticle(it.capture()) }.firstValue
    }

    private fun proposedArticleNumber(view: ArticleView): String? {
        val f = ArticleView::class.java.getDeclaredField("proposedArticleNumber")
        f.isAccessible = true
        return f.get(view) as String?
    }

    private fun newViewWithTaxRates(): ArticleView {
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(emptyList())
        return ArticleView(articleQueries, manageArticle, taxRateQueries, numberRangeQueries, articleTaxVariantsRegistry, articleRecipientRefsRegistry, time = FIXED_TIME_SOURCE)
    }

    @Test
    fun `both identifier fields are freely editable, not read-only`() {
        val view = newViewWithTaxRates()

        assertFalse(field(view, "articleNumberField").isReadOnly, "the article number must be editable in the dialog")
        assertFalse(field(view, "shortHashField").isReadOnly, "the short name must be editable in the dialog")
    }

    @Test
    fun `opening a new article dialog pre-fills the number field with the proposal`() {
        whenever(numberRangeQueries.proposeArticleNumber(any())).thenReturn("ART-00007")
        val view = newViewWithTaxRates()

        readFormData(view, de.littletrotter.ui.view.referencedata.ArticleFormData())

        assertEquals("ART-00007", field(view, "articleNumberField").value)
        assertEquals("ART-00007", proposedArticleNumber(view))
        assertTrue(field(view, "articleNumberField").isVisible)
        // New articles never have a prefilled short name; it is generated on save (ensureShortHash) if blank.
        assertEquals("", field(view, "shortHashField").value)
    }

    @Test
    fun `no active ARTICLE range leaves the field empty but visible and editable`() {
        whenever(numberRangeQueries.proposeArticleNumber(any())).thenReturn(null)
        val view = newViewWithTaxRates()

        readFormData(view, de.littletrotter.ui.view.referencedata.ArticleFormData())

        assertEquals("", field(view, "articleNumberField").value)
        assertNull(proposedArticleNumber(view))
        assertTrue(field(view, "articleNumberField").isVisible)
        assertFalse(field(view, "articleNumberField").isReadOnly, "the article number must be editable in the dialog")
    }

    @Test
    fun `an untouched proposal reaches saveArticle unchanged`() {
        whenever(numberRangeQueries.proposeArticleNumber(any())).thenReturn("ART-00007")
        val view = newViewWithTaxRates()
        val formData = de.littletrotter.ui.view.referencedata.ArticleFormData(name = "Widget")
        readFormData(view, formData)

        val draft = savedDraft(view, formData)

        assertEquals("ART-00007", draft.articleNumber, "die unveränderte Vorschau muss im Entwurf ankommen")
        assertEquals("ART-00007", draft.proposedArticleNumber)
        assertNull(draft.taxVariants, "without the section the stored rows stay as they are")
        assertNull(draft.recipientRefs)
    }

    @Test
    fun `a user-typed article number reaches the draft instead of the proposal`() {
        whenever(numberRangeQueries.proposeArticleNumber(any())).thenReturn("ART-00007")
        val view = newViewWithTaxRates()
        val formData = de.littletrotter.ui.view.referencedata.ArticleFormData(name = "Widget")
        readFormData(view, formData)

        field(view, "articleNumberField").value = "EIGENE-NUMMER"
        field(view, "shortHashField").value = "eigenerHash"

        val draft = savedDraft(view, formData)

        assertEquals("EIGENE-NUMMER", draft.articleNumber, "ein getippter Wert darf nie durch die Vorschau ersetzt werden")
        assertEquals("eigenerHash", draft.shortHash)
        assertEquals("ART-00007", draft.proposedArticleNumber)
    }

    @Test
    fun `leaving both fields blank writes null so auto-assignment on save still applies`() {
        whenever(numberRangeQueries.proposeArticleNumber(any())).thenReturn(null)
        val view = newViewWithTaxRates()
        val formData = de.littletrotter.ui.view.referencedata.ArticleFormData(name = "Widget")
        readFormData(view, formData)

        val draft = savedDraft(view, formData)

        assertNull(draft.articleNumber, "leer bleibt leer -- assignArticleNumber vergibt beim Speichern weiter automatisch")
        assertNull(draft.shortHash, "leer bleibt leer -- ensureShortHash vergibt beim Speichern weiter automatisch")
    }

    @Test
    fun `editing an existing article never proposes never proposes a number, and loads its short hash`() {
        whenever(articleQueries.findArticleById(5L)).thenReturn(
            ArticleDto(id = 5L, name = "Bestand", articleNumber = "ART-00001", shortHash = "aBcDeFgHiJ"),
        )
        val view = newViewWithTaxRates()

        readFormData(view, de.littletrotter.ui.view.referencedata.ArticleFormData(id = 5L))

        verify(numberRangeQueries, org.mockito.kotlin.never()).proposeArticleNumber(any())
        assertNull(proposedArticleNumber(view))
        assertEquals("ART-00001", field(view, "articleNumberField").value)
        assertEquals("aBcDeFgHiJ", field(view, "shortHashField").value)
    }

    @Test
    fun `editing an existing article's identifiers sends the edited values with the stored revision`() {
        val existing = ArticleDto(id = 5L, version = 2L, name = "Bestand", articleNumber = "ART-00001", shortHash = "aBcDeFgHiJ")
        whenever(articleQueries.findArticleById(5L)).thenReturn(existing)
        val view = newViewWithTaxRates()
        val formData = de.littletrotter.ui.view.referencedata.ArticleFormData(id = 5L, name = "Bestand")
        readFormData(view, formData)

        field(view, "articleNumberField").value = "ART-00099"
        field(view, "shortHashField").value = "neuerHash1"

        val draft = savedDraft(view, formData, existing)

        assertEquals(5L, draft.id)
        assertEquals(2L, draft.version)
        assertEquals("ART-00099", draft.articleNumber)
        assertEquals("neuerHash1", draft.shortHash)
        assertNull(draft.proposedArticleNumber)
    }

    @Test
    fun `a NumberCollisionException from saveArticle is surfaced without crashing the UI`() {
        whenever(numberRangeQueries.proposeArticleNumber(any())).thenReturn("ART-00007")
        whenever(manageArticle.saveArticle(any())).thenThrow(NumberCollisionException("ART-00007"))
        val view = newViewWithTaxRates()
        readFormData(view, de.littletrotter.ui.view.referencedata.ArticleFormData())
        field(view, "articleNumberField") // touch, ensures the preview state above is realistic
        val nameFieldRef = ArticleView::class.java.getDeclaredField("nameField").also { it.isAccessible = true }
        (nameFieldRef.get(view) as TextField).value = "Widget"
        view.categoryBox.value = de.littletrotter.shared.catalogue.InvoiceItemCategory.SERVICE
        view.unitBox.value = de.littletrotter.shared.catalogue.InvoiceItemUnit.HOUR

        val saveCurrentForm = de.littletrotter.ui.view.AbstractCrudView::class.java.getDeclaredMethod("saveCurrentForm")
        saveCurrentForm.isAccessible = true
        // Does not throw: base class catches all persistForm exceptions, shows notification instead.
        saveCurrentForm.invoke(view)
    }

    @Test
    fun `a typed article number over 60 characters is surfaced without crashing the UI`() {
        whenever(numberRangeQueries.proposeArticleNumber(any())).thenReturn(null)
        whenever(manageArticle.saveArticle(any())).thenThrow(InvalidParameterException("Artikelnummer darf höchstens 60 Zeichen haben."))
        val view = newViewWithTaxRates()
        // readFormData's binder.readBean(formData) already selects the default category/unit
        // (SERVICE/HOUR) from ArticleFormData()'s own defaults, so only name and the article
        // number itself need to be set for this boundary.
        readFormData(view, de.littletrotter.ui.view.referencedata.ArticleFormData())
        field(view, "articleNumberField").value = "A".repeat(61)
        val nameFieldRef = ArticleView::class.java.getDeclaredField("nameField").also { it.isAccessible = true }
        (nameFieldRef.get(view) as TextField).value = "Widget"

        val saveCurrentForm = de.littletrotter.ui.view.AbstractCrudView::class.java.getDeclaredMethod("saveCurrentForm")
        saveCurrentForm.isAccessible = true
        // Does not throw: the save rejects the number (ArticleNumber's own length check) and the
        // base class's generic catch shows a notification with the message instead of crashing.
        saveCurrentForm.invoke(view)

        assertEquals("A".repeat(61), argumentCaptor<ArticleDraft>().also { verify(manageArticle).saveArticle(it.capture()) }.firstValue.articleNumber)
    }

    // ── Description column: CSS ellipsis + mouse-over tooltip instead of a manual cutoff ──────────

    @Test
    fun `the description column renders the ellipsis class and carries the full description as a tooltip`() {
        val view = newViewWithTaxRates()
        val longDescription = "Ein sehr ausführlicher Artikeltext, der die Kurzspalte in der Übersicht ohne Kürzung sprengen würde."
        val article = ArticleDto(id = 3L, name = "Widget", description = longDescription)
        val grid = view.grid
        val column = grid.columns.first { it.headerText == de.littletrotter.ui.i18n.UiTranslations.tr("serviceCatalog.description.short") }

        val cell = column.renderer.javaClass.getMethod("createComponent", Any::class.java)
            .invoke(column.renderer, article) as com.vaadin.flow.component.html.Span

        assertTrue(cell.element.classList.contains("lt-cell-ellipsis"))
        assertEquals(longDescription, cell.element.getAttribute("title"))
        assertEquals(longDescription, cell.text, "the full text stays in the DOM — CSS clips it visually, unlike the old 50-char cutoff")
    }
}
