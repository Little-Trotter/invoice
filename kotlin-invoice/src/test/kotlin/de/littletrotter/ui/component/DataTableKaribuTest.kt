package de.littletrotter.ui.component

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.data.renderer.ComponentRenderer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [DataTable] makes every column resizable by default, regardless of which
 * `addColumn`/`addComponentColumn` overload a view actually uses (see the grep survey across the
 * 22 views this component backs), and offers [ellipsizedTextColumn] as the shared helper for
 * long-free-text columns that need a mouse-over tooltip.
 */
class DataTableKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    /** The [ComponentRenderer]'s actual rendered [Component] for an arbitrary [item] — not
     *  necessarily a row the grid has fetched. Karibu's own `getPresentationValue` stringifies a
     *  ComponentRenderer's output instead of returning the [Component] itself, so this reaches
     *  the renderer's `createComponent` through reflection: [column]'s own `renderer` property is
     *  already checked-typed as `Renderer<T>`, and only the reflective invocation (not a Kotlin
     *  generic cast) bridges to the concrete [ComponentRenderer]. */
    private fun <T> componentCellOf(column: Grid.Column<T>, item: T): Component =
        column.renderer.javaClass.getMethod("createComponent", Any::class.java).invoke(column.renderer, item) as Component

    // ── Part 1 — every used addColumn/addComponentColumn overload defaults to resizable ─────────

    @Test
    fun `addColumn(ValueProvider) - property reference or lambda - is resizable by default`() {
        val table = DataTable(String::class.java, false)

        val column = table.addColumn { it.uppercase() }

        assertTrue(column.isResizable, "the most common overload (property references, plain-value lambdas) must default to resizable")
    }

    @Test
    fun `addColumn(Renderer) - e_g_ ComponentRenderer, as used by InvoiceListView - is resizable by default`() {
        val table = DataTable(String::class.java, false)

        val column = table.addColumn(ComponentRenderer<Span, String> { s -> Span(s) })

        assertTrue(column.isResizable, "the Renderer overload (ComponentRenderer wrapped explicitly) must default to resizable")
    }

    @Test
    fun `addComponentColumn is resizable by default`() {
        val table = DataTable(String::class.java, false)

        val column = table.addComponentColumn { Span(it) }

        assertTrue(column.isResizable, "the addComponentColumn overload, used across most views for chips/badges/actions, must default to resizable")
    }

    @Test
    fun `a column can still opt out of resizing for a reasoned exception - e_g_ a pure action column`() {
        val table = DataTable(String::class.java, false)

        val column = table.addComponentColumn { Span(it) }.also { it.setResizable(false) }

        assertTrue(!column.isResizable, "individual views must still be able to opt a column back out")
    }

    // ── Part 2 — the shared ellipsizedTextColumn helper ───────────────────────────────────────

    @Test
    fun `ellipsizedTextColumn renders the shared ellipsis class and carries the full text as a tooltip`() {
        val table = DataTable(String::class.java, false)
        val longText = "Diese Meldung ist sehr lang und würde in einer schmalen Spalte ohne Kürzung abgeschnitten."

        val column = table.ellipsizedTextColumn { it }

        val cell = componentCellOf(column, longText) as Span

        assertTrue(cell.element.classList.contains("lt-cell-ellipsis"), "must use the shared single-line CSS-ellipsis class")
        assertEquals(longText, cell.element.getAttribute("title"), "the full text must stay reachable on mouse-over")
        assertEquals(longText, cell.text, "unlike character truncation, the full text stays in the DOM — CSS clips it visually")
    }

    @Test
    fun `ellipsizedTextColumn carries no tooltip for empty text`() {
        val table = DataTable(String::class.java, false)

        val column = table.ellipsizedTextColumn { it }

        val cell = componentCellOf(column, "") as Span

        assertNull(cell.element.getAttribute("title"), "an empty value has nothing worth a tooltip")
    }

    @Test
    fun `ellipsizedTextColumn is itself resizable by default, like every other DataTable column`() {
        val table = DataTable(String::class.java, false)

        val column = table.ellipsizedTextColumn { it }

        assertTrue(column.isResizable)
    }

    // ── The shared twoLineTextColumn helper (Material two-line identity cell) ────────────────────

    private data class Item(val primary: String, val secondary: String?)

    private fun renderTwoLine(column: Grid.Column<Item>, item: Item): Div =
        componentCellOf(column, item) as Div

    @Test
    fun `twoLineTextColumn renders primary and secondary spans with the shared classes and tooltips`() {
        val table = DataTable(Item::class.java, false)

        val column = table.twoLineTextColumn({ it.primary }, { it.secondary })
        val cell = renderTwoLine(column, Item("Neckar", "Vollständiger B2B-Feldsatz"))

        assertTrue(cell.hasClassName("lt-cell-two-line"))
        val primary = cell.children.toList()[0]
        val secondary = cell.children.toList()[1]
        assertTrue(primary.element.classList.contains("lt-cell-two-line__primary"))
        assertEquals("Neckar", primary.element.getAttribute("title"))
        assertTrue(secondary.element.classList.contains("lt-cell-two-line__secondary"))
        assertEquals("Vollständiger B2B-Feldsatz", secondary.element.getAttribute("title"))
    }

    @Test
    fun `twoLineTextColumn - B1 - omits the secondary span for null, empty and blank descriptions`() {
        val table = DataTable(Item::class.java, false)
        val column = table.twoLineTextColumn({ it.primary }, { it.secondary })

        for (blank in listOf(null, "", "   ")) {
            val cell = renderTwoLine(column, Item("Kunde", blank))

            val children = cell.children.toList()
            assertEquals(1, children.size, "no secondary line for secondary=$blank — no span, so no title to carry")
            assertTrue(children[0].element.classList.contains("lt-cell-two-line__primary"))
            assertEquals("Kunde", children[0].element.getAttribute("title"), "the primary line still carries its own tooltip")
        }
    }

    @Test
    fun `twoLineTextColumn - B2 - a 500-char secondary without spaces renders fully with a tooltip`() {
        val table = DataTable(Item::class.java, false)
        val longSecondary = "x".repeat(500)

        val column = table.twoLineTextColumn({ it.primary }, { it.secondary })
        val cell = renderTwoLine(column, Item("Kunde", longSecondary))

        assertEquals(2, cell.children.toList().size)
        val secondary = cell.children.toList()[1]
        assertEquals(longSecondary, secondary.element.getAttribute("title"))
        assertTrue(secondary.element.classList.contains("lt-cell-two-line__secondary"))
    }

    @Test
    fun `twoLineTextColumn - B3 - a 200-char primary renders single-line ellipsized with a tooltip`() {
        val table = DataTable(Item::class.java, false)
        val longPrimary = "y".repeat(200)

        val column = table.twoLineTextColumn({ it.primary }, { it.secondary })
        val cell = renderTwoLine(column, Item(longPrimary, null))

        val primary = cell.children.toList()[0]
        assertTrue(primary.element.classList.contains("lt-cell-two-line__primary"))
        assertEquals(longPrimary, primary.element.getAttribute("title"))
        assertEquals(1, cell.children.toList().size, "still no secondary line — this case has none")
    }

    @Test
    fun `twoLineTextColumn is itself resizable by default, like every other DataTable column`() {
        val table = DataTable(Item::class.java, false)

        val column = table.twoLineTextColumn({ it.primary }, { it.secondary })

        assertTrue(column.isResizable)
    }

    // ── multiselect=true must set SelectionMode.MULTI, not autoCreateColumns ─────────────────────

    @Test
    fun `multiselect=true selects two rows without replacing the first`() {
        val table = DataTable(String::class.java, true)
        table.setItems("a", "b", "c")

        table.select("a")
        table.select("b")

        assertEquals(2, table.selectedItems.size, "selecting a second row must add to the selection, not replace it")
        assertTrue(table.selectedItems.containsAll(listOf("a", "b")))
    }

    @Test
    fun `multiselect=false keeps the default single-selection mode`() {
        val table = DataTable(String::class.java, false)
        table.setItems("a", "b")

        table.select("a")
        table.select("b")

        assertEquals(1, table.selectedItems.size, "single-select mode must still replace the selection")
    }

    @Test
    fun `twoLineTextColumn carries no tooltip for an empty primary text`() {
        val table = DataTable(Item::class.java, false)
        val column = table.twoLineTextColumn({ it.primary }, { it.secondary })

        val cell = renderTwoLine(column, Item("", null))

        val primary = cell.children.toList()[0]
        assertNull(primary.element.getAttribute("title"), "an empty primary value has nothing worth a tooltip")
    }

    // ── DataTreeTable: same resizable-by-default contract, for every overload ────────────

    @Test
    fun `DataTreeTable addColumn(ValueProvider) is resizable by default`() {
        val table = DataTreeTable(String::class.java)

        val column = table.addColumn { it.uppercase() }

        assertTrue(column.isResizable)
    }

    @Test
    fun `DataTreeTable addColumn(Renderer) is resizable by default`() {
        val table = DataTreeTable(String::class.java)

        val column = table.addColumn(ComponentRenderer<Span, String> { s -> Span(s) })

        assertTrue(column.isResizable)
    }

    @Test
    fun `DataTreeTable addComponentColumn is resizable by default`() {
        val table = DataTreeTable(String::class.java)

        val column = table.addComponentColumn { Span(it) }

        assertTrue(column.isResizable)
    }

    @Test
    fun `DataTreeTable addComponentHierarchyColumn is resizable by default`() {
        val table = DataTreeTable(String::class.java)

        val column = table.addComponentHierarchyColumn { Span(it) }

        assertTrue(column.isResizable, "the hierarchy column (day-grouped Druckaufträge) must default to resizable too")
    }

    @Test
    fun `the two-argument constructor never auto-creates reflection columns, multiselect or not`() {
        val single = DataTable(String::class.java, false)
        val multi = DataTable(String::class.java, true)

        assertEquals(0, single.columns.size, "no explicit columns were added — auto-columns must not fill them in")
        assertEquals(0, multi.columns.size, "same for the multiselect grid — this is exactly what broke ItemDto's 22-column grid (L10)")
    }
}
