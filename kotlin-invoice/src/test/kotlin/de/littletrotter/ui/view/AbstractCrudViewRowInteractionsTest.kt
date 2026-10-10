package de.littletrotter.ui.view

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._clickItem
import com.github.mvysny.kaributesting.v10._doubleClickItem
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.grid.Grid
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Verifies [AbstractCrudView.wireEditorRowActions], the shared row interaction
 * wiring every classic reference-data tab (payment terms, tax rates, per-diem/hotel/mileage rates,
 * number ranges, PDF templates) now goes through instead of each tab wiring its own row-click:
 * a row click only selects, and a pencil-icon action column / row double-click are the sole ways
 * to open the editor dialog. Exercised against a minimal fake subclass rather than any one real
 * tab, since the wiring itself is identical for all of them.
 */
class AbstractCrudViewRowInteractionsTest {

    /** Trivial [AbstractCrudView] over plain strings — only [AbstractCrudView.wireEditorRowActions]
     *  is under test here, so every other abstract member is a stub that's never exercised. */
    private open class FakeCrudView(openWith: ((String) -> Unit)? = null) : AbstractCrudView<String, String>() {
        val grid = Grid<String>()
        val openedWith = mutableListOf<String>()

        override var currentFormData: String = ""
        override fun idOf(formData: String): Long? = null
        override fun formDataFrom(entity: String?): String = entity.orEmpty()
        override fun createWritableForm(id: Long?): String = ""
        override fun readFormData(formData: String) {}
        override fun writeValidatedForm(targetFormData: String): Boolean = true
        override fun findEntityById(id: Long): String? = null
        override fun persistForm(formData: String, current: String?) {}
        override fun deleteEntityById(id: Long) {}
        override fun refreshGridData() {}

        fun isEditorDialogOpen(): Boolean = editorDialog.isOpened

        init {
            if (openWith != null) {
                wireEditorRowActions(grid) { entity -> openedWith.add(entity); openWith(entity) }
            } else {
                wireEditorRowActions(grid) { entity -> openedWith.add(entity); openEditor(entity) }
            }
        }
    }

    /** Unlike [FakeCrudView] (which always passes an explicit `openWith` lambda, even for its
     *  "default" case), this subclass calls `wireEditorRowActions(grid)` with no second argument
     *  at all — the real Kotlin default-parameter value (`openWith: (T) -> Unit = { openEditor(it) }`)
     *  is otherwise never actually invoked from a test. */
    private class PureDefaultFakeCrudView : AbstractCrudView<String, String>() {
        val grid = Grid<String>()
        override var currentFormData: String = ""
        override fun idOf(formData: String): Long? = null
        override fun formDataFrom(entity: String?): String = entity.orEmpty()
        override fun createWritableForm(id: Long?): String = ""
        override fun readFormData(formData: String) {}
        override fun writeValidatedForm(targetFormData: String): Boolean = true
        override fun findEntityById(id: Long): String? = null
        override fun persistForm(formData: String, current: String?) {}
        override fun deleteEntityById(id: Long) {}
        override fun refreshGridData() {}

        fun isEditorDialogOpen(): Boolean = editorDialog.isOpened

        init {
            wireEditorRowActions(grid)
        }
    }

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    @Test
    fun `grid selection mode is single`() {
        val view = FakeCrudView()

        assertEquals(Grid.SelectionMode.SINGLE, view.grid.selectionMode)
    }

    /** The edit-icon column's rendered [Button] for [item] — Karibu's own `getPresentationValue`
     *  stringifies a [ComponentRenderer]'s output instead of returning the [Component] itself, so
     *  this reaches the renderer's `createComponent` through reflection: [column]'s own `renderer`
     *  property is already checked-typed as `Renderer<T>`, and only the reflective invocation
     *  (not a Kotlin generic cast) bridges to the concrete [ComponentRenderer]. */
    private fun <T> componentCellOf(column: Grid.Column<T>, item: T): com.vaadin.flow.component.Component =
        column.renderer.javaClass.getMethod("createComponent", Any::class.java).invoke(column.renderer, item)
            as com.vaadin.flow.component.Component

    @Test
    fun `an edit-icon action column is added with the shared aria-label`() {
        val view = FakeCrudView()
        view.grid.setItems("Alpha")

        val editColumn = view.grid.columns.last()
        val button = componentCellOf(editColumn, "Alpha") as Button

        assertEquals(UiTranslations.tr("referenceData.edit"), button.element.getAttribute("aria-label"))
    }

    @Test
    fun `clicking the edit-icon button opens the editor for that row`() {
        val view = FakeCrudView()
        view.grid.setItems("Alpha")

        val editColumn = view.grid.columns.last()
        val button = componentCellOf(editColumn, "Alpha") as Button
        button.click()

        assertEquals(listOf("Alpha"), view.openedWith)
    }

    @Test
    fun `a single row click only selects - it must not open the editor`() {
        val view = FakeCrudView()
        view.grid.setItems("Alpha")

        view.grid._clickItem(0)

        assertEquals("Alpha", view.grid.asSingleSelect().value)
        assertTrue(view.openedWith.isEmpty(), "a row click must only select, never open the dialog")
    }

    @Test
    fun `a double click on the row opens the editor`() {
        val view = FakeCrudView()
        view.grid.setItems("Alpha")

        view.grid._doubleClickItem(0)

        assertEquals(listOf("Alpha"), view.openedWith)
    }

    @Test
    fun `the default openWith delegates to openEditor, which opens the shared dialog`() {
        val view = FakeCrudView()
        view.grid.setItems("Alpha")

        view.grid._doubleClickItem(0)

        assertTrue(view.isEditorDialogOpen(), "double-click must reach openEditor via the default openWith")
    }

    @Test
    fun `a custom openWith wrapper is used instead of openEditor when provided`() {
        val calls = mutableListOf<String>()
        val view = FakeCrudView(openWith = { calls.add(it) })
        view.grid.setItems("Alpha")

        view.grid._doubleClickItem(0)

        assertEquals(listOf("Alpha"), calls)
        assertTrue(!view.isEditorDialogOpen(), "a custom openWith (e.g. TaxRatesTab's in-use check) replaces, not supplements, openEditor")
    }

    @Test
    fun `the real default parameter (no explicit openWith argument at all) also opens via openEditor`() {
        val view = PureDefaultFakeCrudView()
        view.grid.setItems("Alpha")

        view.grid._doubleClickItem(0)

        assertTrue(view.isEditorDialogOpen())
    }
}
