package de.littletrotter.ui.view.masterdata

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._fetch
import com.github.mvysny.kaributesting.v10._size
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.tabs.Tabs
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.masterdata.ArticleRecipientRefsExtensionRegistry
import de.littletrotter.application.masterdata.ArticleTaxVariantsExtensionRegistry
import de.littletrotter.application.reference.port.inbound.ArticleQueryPort
import de.littletrotter.application.reference.port.inbound.ManageArticleUseCase
import de.littletrotter.application.reference.port.inbound.NumberRangeQueryPort
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.application.dto.ArticleDraft
import de.littletrotter.application.dto.ArticleDto
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.ui.i18n.UiTranslations
import de.littletrotter.ui.view.AbstractCrudView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.Mockito.clearInvocations
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import de.littletrotter.test.FIXED_TIME_SOURCE

/** Synthetic catalogue fixtures derived from the datagen category/unit contract; no external data. */
class ArticleViewCategoryTabsKaribuTest {
    private val articleQueries: ArticleQueryPort = mock()
    private val manageArticle: ManageArticleUseCase = mock()
    private val taxRateQueries: TaxRateQueryPort = mock()
    private val numberRangeQueries: NumberRangeQueryPort = mock()
    private val taxVariants: ArticleTaxVariantsExtensionRegistry = mock()
    private val recipientRefs: ArticleRecipientRefsExtensionRegistry = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(emptyList())
        whenever(articleQueries.findArticles(any(), any(), anyOrNull(), anyOrNull(), any(), any(), anyOrNull())).thenReturn(emptyList())
        whenever(articleQueries.countArticles(anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(0L)
    }

    @AfterEach
    fun teardown() = MockVaadin.tearDown()

    private fun <T : Any> field(view: ArticleView, name: String, type: kotlin.reflect.KClass<T>): T =
        type.java.cast(ArticleView::class.java.getDeclaredField(name).also { it.isAccessible = true }.get(view))

    private fun <T : Any> crudField(view: ArticleView, name: String, type: kotlin.reflect.KClass<T>): T =
        type.java.cast(AbstractCrudView::class.java.getDeclaredField(name).also { it.isAccessible = true }.get(view))

    private fun open(view: ArticleView, article: ArticleDto? = null) {
        AbstractCrudView::class.java.getDeclaredMethod("openEditor", Any::class.java)
            .also { it.isAccessible = true }.invoke(view, article)
        assertTrue(crudField(view, "editorDialog", Dialog::class).isOpened)
    }

    @Test
    fun `tabs show all categories in order and initially select material`() {
        val view = ArticleView(articleQueries, manageArticle, taxRateQueries, numberRangeQueries, taxVariants, recipientRefs, time = FIXED_TIME_SOURCE)
        val tabs = field(view, "categoryTabs", Tabs::class)
        assertEquals(
            listOf("material", "unitPrice", "service").map { UiTranslations.tr("serviceCatalog.category.$it") },
            tabs.children.map { it.element.text }.toList(),
        )
        assertEquals(0, tabs.selectedIndex)
    }

    @Test
    fun `category tabs replace the redundant category grid column but preserve the locked dialog field`() {
        val view = ArticleView(articleQueries, manageArticle, taxRateQueries, numberRangeQueries, taxVariants, recipientRefs, time = FIXED_TIME_SOURCE)
        assertNull(view.grid.getColumnByKey("category"))
        open(view)
        assertTrue(view.categoryBox.isReadOnly)
        assertEquals(InvoiceItemCategory.PRODUCT, view.categoryBox.value)
    }

    @Test
    fun `trips remain invoice only and cannot be selected when adding catalogue positions`() {
        val view = ArticleView(articleQueries, manageArticle, taxRateQueries, numberRangeQueries, taxVariants, recipientRefs, time = FIXED_TIME_SOURCE)
        open(view)
        val categories = view.categoryBox.listDataView.items.toList()
        assertEquals(listOf(InvoiceItemCategory.PRODUCT, InvoiceItemCategory.UNIT_PRICE, InvoiceItemCategory.SERVICE), categories)
        assertTrue(ServiceCatalogSection.entries.any { it.category == InvoiceItemCategory.TRIP })
    }

    @ParameterizedTest
    @CsvSource("0,PRODUCT", "1,UNIT_PRICE", "2,SERVICE")
    fun `switching tabs scopes both server fetch and count with existing search`(index: Int, category: InvoiceItemCategory) {
        val view = ArticleView(articleQueries, manageArticle, taxRateQueries, numberRangeQueries, taxVariants, recipientRefs, time = FIXED_TIME_SOURCE)
        field(view, "filterField", TextField::class).value = "  Synthetic  "
        field(view, "numberFilterField", TextField::class).value = "42"
        field(view, "categoryTabs", Tabs::class).selectedIndex = index
        clearInvocations(articleQueries)
        view.grid._fetch(0, 25)
        view.grid._size()
        verify(articleQueries).findArticles(0, 25, "42", "Synthetic", "name", SortDirection.ASC, category)
        verify(articleQueries).countArticles("42", "Synthetic", category)
    }

    @ParameterizedTest
    @CsvSource("0,PRODUCT,PIECE,CUBIC_METER", "1,UNIT_PRICE,SQUARE_METER,METER", "2,SERVICE,HOUR,DAY")
    fun `new position uses locked tab category and default unit and preserves both through save and reopen`(
        index: Int,
        category: InvoiceItemCategory,
        unit: InvoiceItemUnit,
        chosenUnit: InvoiceItemUnit,
    ) {
        val view = ArticleView(articleQueries, manageArticle, taxRateQueries, numberRangeQueries, taxVariants, recipientRefs, time = FIXED_TIME_SOURCE)
        field(view, "categoryTabs", Tabs::class).selectedIndex = index
        assertEquals(
            UiTranslations.tr("serviceCatalog.action.new", UiTranslations.tr(ServiceCatalogSection.forCategory(category).labelKey)),
            field(view, "newButton", Button::class).text,
        )
        open(view)
        val categoryBox = view.categoryBox
        val unitBox = view.unitBox
        val label = UiTranslations.tr(ServiceCatalogSection.forCategory(category).labelKey)
        assertEquals(category, categoryBox.value)
        assertTrue(categoryBox.isReadOnly)
        assertEquals(unit, unitBox.value)
        unitBox.value = chosenUnit
        assertTrue(crudField(view, "editorDialog", Dialog::class).headerTitle.contains(label))
        field(view, "nameField", TextField::class).value = if (category == InvoiceItemCategory.PRODUCT) "Server Hardware" else "Backend Development"
        whenever(manageArticle.saveArticle(any())).thenAnswer { invocation ->
            val draft = invocation.getArgument<ArticleDraft>(0)
            ArticleDto(id = 42L, name = draft.name, category = draft.category, defaultUnit = draft.defaultUnit)
        }
        crudField(view, "saveButton", Button::class).click()
        val captured = argumentCaptor<ArticleDraft>()
        verify(manageArticle).saveArticle(captured.capture())
        val draft = captured.firstValue
        assertEquals(category, draft.category)
        assertEquals(chosenUnit, draft.defaultUnit)
        val saved = ArticleDto(id = 42L, name = draft.name, category = draft.category, defaultUnit = draft.defaultUnit)
        assertFalse(crudField(view, "editorDialog", Dialog::class).isOpened)
        whenever(articleQueries.findArticleById(42L)).thenReturn(saved)
        // An existing row's category, not whichever list tab is now selected, owns its editor.
        field(view, "categoryTabs", Tabs::class).selectedIndex = (index + 1) % 3
        open(view, saved)
        assertEquals(category, categoryBox.value)
        assertTrue(categoryBox.isReadOnly)
        assertEquals(chosenUnit, unitBox.value, "loading the allowed units must not clear the saved selection")
        assertTrue(crudField(view, "editorDialog", Dialog::class).headerTitle.contains(label))
    }

    @Test
    fun `a saved default hour unit remains selected on service reopen`() {
        val saved = ArticleDto(
            id = 42L,
            name = "Backend Development",
            category = InvoiceItemCategory.SERVICE,
            defaultUnit = InvoiceItemUnit.HOUR,
        )
        whenever(articleQueries.findArticleById(42L)).thenReturn(saved)
        val view = ArticleView(articleQueries, manageArticle, taxRateQueries, numberRangeQueries, taxVariants, recipientRefs, time = FIXED_TIME_SOURCE)
        open(view, saved)
        assertEquals(InvoiceItemUnit.HOUR, view.unitBox.value)
    }
}
