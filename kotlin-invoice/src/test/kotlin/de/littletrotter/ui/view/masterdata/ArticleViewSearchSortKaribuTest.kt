package de.littletrotter.ui.view.masterdata

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._fireShortcut
import com.github.mvysny.kaributesting.v10._fetch
import com.github.mvysny.kaributesting.v10._size
import com.vaadin.flow.component.Key
import com.vaadin.flow.component.tabs.Tabs
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.grid.GridSortOrder
import de.littletrotter.application.masterdata.ArticleRecipientRefsExtensionRegistry
import de.littletrotter.application.masterdata.ArticleTaxVariantsExtensionRegistry
import de.littletrotter.application.reference.port.inbound.ArticleQueryPort
import de.littletrotter.application.reference.port.inbound.ManageArticleUseCase
import de.littletrotter.application.reference.port.inbound.NumberRangeQueryPort
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.application.dto.ArticleDto
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.domain.shared.SortDirection
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * The Artikel grid's two-field search, sorting and category wiring:
 *
 * - the compact Nr. field and the wide text field filter live and AND-combine server-side
 *   (Nr. → articleNumber only, text → name/description only, page and count from the same
 *   predicate);
 * - Enter in the Nr. field with exactly ONE server-side match opens that article's edit
 *   dialog directly; with 0 or >1 matches Enter does nothing (no error noise);
 * - the Artikel-Nr column is sortable (the PO searches by number, so the number must order the
 *   grid too) and its header clicks map to the `articleNumber` sort field;
 * - the default NAME/ASC order is set as a real grid sort order, so the header shows an
 *   indicator and the FIRST click on "Name" flips to DESC instead of re-selecting the order
 *   the grid already displays (the "dead sorting" impression);
 * - the category filter constrains fetch AND count server-side — no client-side filtering of
 *   the fetched page (the paging corruption `ReceiptRepository.findAssignable` documents).
 */
class ArticleViewSearchSortKaribuTest {

    private val articleQueries: ArticleQueryPort = mock()

    private val manageArticle: ManageArticleUseCase = mock()
    private val taxRateQueries: TaxRateQueryPort = mock()
    private val numberRangeQueries: NumberRangeQueryPort = mock()
    private val articleTaxVariantsRegistry: ArticleTaxVariantsExtensionRegistry = mock()
    private val articleRecipientRefsRegistry: ArticleRecipientRefsExtensionRegistry = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        whenever(taxRateQueries.findAllTaxRates()).thenReturn(emptyList())
    }

    @AfterEach
    fun teardown() {
        MockVaadin.tearDown()
    }

    private fun newView(): ArticleView =
        ArticleView(articleQueries, manageArticle, taxRateQueries, numberRangeQueries, articleTaxVariantsRegistry, articleRecipientRefsRegistry, time = FIXED_TIME_SOURCE)

    private fun textFieldOf(view: ArticleView, name: String): TextField {
        val f = ArticleView::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(view) as TextField
    }

    private fun numberFilterOf(view: ArticleView): TextField = textFieldOf(view, "numberFilterField")

    private fun editorDialogOf(view: ArticleView): com.vaadin.flow.component.dialog.Dialog {
        val f = de.littletrotter.ui.view.AbstractCrudView::class.java.getDeclaredField("editorDialog")
        f.isAccessible = true
        return f.get(view) as com.vaadin.flow.component.dialog.Dialog
    }

    private fun gridOf(view: ArticleView): Grid<ArticleDto> = view.grid

    private fun categoryTabsOf(view: ArticleView): Tabs {
        val f = ArticleView::class.java.getDeclaredField("categoryTabs")
        f.isAccessible = true
        return f.get(view) as Tabs
    }

    private fun fetchAndCount(grid: Grid<ArticleDto>) {
        grid._fetch(0, 50)
        grid._size()
    }

    @Test
    fun `article number column exists, carries its key and is sortable`() {
        val view = newView()
        val column = gridOf(view).getColumnByKey("articleNumber")

        assertNotNull(column, "the Artikel-Nr column must have an explicit key")
        assertTrue(column!!.isSortable, "the Artikel-Nr column must be sortable")
    }

    @Test
    fun `the default NAME ASC order is visible as a real sort order on the name column`() {
        val view = newView()
        val sortOrder = gridOf(view).sortOrder

        assertEquals(1, sortOrder.size)
        assertEquals("name", sortOrder[0].sorted.key)
        assertEquals(com.vaadin.flow.data.provider.SortDirection.ASCENDING, sortOrder[0].direction)
    }

    @Test
    fun `sorting by the article number column reaches the facade as articleNumber DESC`() {
        val view = newView()
        val grid = gridOf(view)

        grid.sort(listOf(GridSortOrder(grid.getColumnByKey("articleNumber"), com.vaadin.flow.data.provider.SortDirection.DESCENDING)))
        Mockito.clearInvocations(articleQueries)
        fetchAndCount(grid)

        verify(articleQueries).findArticles(0, 50, null, null, "articleNumber", SortDirection.DESC, InvoiceItemCategory.PRODUCT)
        verify(articleQueries).countArticles(null, null, InvoiceItemCategory.PRODUCT)
    }

    @Test
    fun `clearing the sort falls back to NAME ASC`() {
        val view = newView()
        val grid = gridOf(view)

        grid.sort(listOf(GridSortOrder(grid.getColumnByKey("defaultUnit"), com.vaadin.flow.data.provider.SortDirection.ASCENDING)))
        grid.sort(emptyList())
        Mockito.clearInvocations(articleQueries)
        fetchAndCount(grid)

        verify(articleQueries).findArticles(0, 50, null, null, "name", SortDirection.ASC, InvoiceItemCategory.PRODUCT)
    }

    @Test
    fun `the category filter constrains fetch and count server-side`() {
        val view = newView()
        val grid = gridOf(view)

        categoryTabsOf(view).selectedIndex = 0
        Mockito.clearInvocations(articleQueries)
        fetchAndCount(grid)

        verify(articleQueries).findArticles(0, 50, null, null, "name", SortDirection.ASC, InvoiceItemCategory.PRODUCT)
        verify(articleQueries).countArticles(null, null, InvoiceItemCategory.PRODUCT)
    }

    @Test
    fun `category filter and column sort combine into one server-side query`() {
        val view = newView()
        val grid = gridOf(view)

        categoryTabsOf(view).selectedIndex = 2
        grid.sort(listOf(GridSortOrder(grid.getColumnByKey("articleNumber"), com.vaadin.flow.data.provider.SortDirection.ASCENDING)))
        Mockito.clearInvocations(articleQueries)
        fetchAndCount(grid)

        verify(articleQueries).findArticles(0, 50, null, null, "articleNumber", SortDirection.ASC, InvoiceItemCategory.SERVICE)
        verify(articleQueries).countArticles(null, null, InvoiceItemCategory.SERVICE)
    }

    // ── the two-field search itself ──────────────────────────────

    @Test
    fun `the Nr field filters live on articleNumber only, the text field stays null`() {
        val view = newView()
        val grid = gridOf(view)

        numberFilterOf(view).value = "36"
        Mockito.clearInvocations(articleQueries)
        fetchAndCount(grid)

        verify(articleQueries).findArticles(0, 50, "36", null, "name", SortDirection.ASC, InvoiceItemCategory.PRODUCT)
        verify(articleQueries).countArticles("36", null, InvoiceItemCategory.PRODUCT)
    }

    @Test
    fun `both fields AND-combine with the category into one server-side query`() {
        val view = newView()
        val grid = gridOf(view)

        numberFilterOf(view).value = "36"
        textFieldOf(view, "filterField").value = "Pumpe"
        categoryTabsOf(view).selectedIndex = 0
        Mockito.clearInvocations(articleQueries)
        fetchAndCount(grid)

        verify(articleQueries).findArticles(0, 50, "36", "Pumpe", "name", SortDirection.ASC, InvoiceItemCategory.PRODUCT)
        verify(articleQueries).countArticles("36", "Pumpe", InvoiceItemCategory.PRODUCT)
    }

    // ── Enter in the Nr. field opens the single match ────────────

    @Test
    fun `Enter in the Nr field with exactly one server-side match opens the edit dialog`() {
        val single = ArticleDto(id = 36L, name = "Wartung", articleNumber = "ART-2026-00036")
        whenever(articleQueries.countArticles("36", null, InvoiceItemCategory.PRODUCT)).thenReturn(1L)
        whenever(articleQueries.findArticles(0, 1, "36", null, "name", SortDirection.ASC, InvoiceItemCategory.PRODUCT))
            .thenReturn(listOf(single))
        val view = newView()
        com.vaadin.flow.component.UI.getCurrent().add(view)

        numberFilterOf(view).value = "36"
        numberFilterOf(view)._fireShortcut(Key.ENTER)

        assertTrue(editorDialogOf(view).isOpened, "count == 1 must open the single match's edit dialog")
        verify(articleQueries, Mockito.atLeastOnce()).findArticleById(36L)
    }

    @Test
    fun `Enter with zero matches does nothing`() {
        whenever(articleQueries.countArticles("nix", null, InvoiceItemCategory.PRODUCT)).thenReturn(0L)
        val view = newView()
        com.vaadin.flow.component.UI.getCurrent().add(view)

        numberFilterOf(view).value = "nix"
        numberFilterOf(view)._fireShortcut(Key.ENTER)

        assertFalse(editorDialogOf(view).isOpened, "count == 0 must not open any dialog")
        verify(articleQueries, Mockito.never()).findArticles(0, 1, "nix", null, "name", SortDirection.ASC, InvoiceItemCategory.PRODUCT)
    }

    @Test
    fun `Enter with more than one match does nothing`() {
        whenever(articleQueries.countArticles("ART-", null, InvoiceItemCategory.PRODUCT)).thenReturn(2L)
        val view = newView()
        com.vaadin.flow.component.UI.getCurrent().add(view)

        numberFilterOf(view).value = "ART-"
        numberFilterOf(view)._fireShortcut(Key.ENTER)

        assertFalse(editorDialogOf(view).isOpened, "count > 1 must not open any dialog")
        verify(articleQueries, Mockito.never()).findArticles(0, 1, "ART-", null, "name", SortDirection.ASC, InvoiceItemCategory.PRODUCT)
    }

    @Test
    fun `boundary - a stale count of one whose fetch returns no row does not open any dialog`() {
        // Defensive: countArticles and the subsequent findArticles(0, 1, ...) are two separate
        // calls; a concurrent change between them could make the fetch empty even though the
        // count still said 1 — singleOrNull() then returns null and openEditor must not run.
        whenever(articleQueries.countArticles("36", null, InvoiceItemCategory.PRODUCT)).thenReturn(1L)
        whenever(articleQueries.findArticles(0, 1, "36", null, "name", SortDirection.ASC, InvoiceItemCategory.PRODUCT))
            .thenReturn(emptyList())
        val view = newView()
        com.vaadin.flow.component.UI.getCurrent().add(view)

        numberFilterOf(view).value = "36"
        numberFilterOf(view)._fireShortcut(Key.ENTER)

        assertFalse(editorDialogOf(view).isOpened, "a since-changed empty fetch must not open a dialog")
    }

    @Test
    fun `boundary - updatePageTitle is a no-op once the view has no attached UI`() {
        val view = newView()
        val method = ArticleView::class.java.getDeclaredMethod("updatePageTitle")
        method.isAccessible = true
        MockVaadin.tearDown()

        org.junit.jupiter.api.Assertions.assertDoesNotThrow { method.invoke(view) }
    }
}
