package de.littletrotter.ui.view.journal

import com.github.mvysny.kaributesting.v10._getItemDetailsComponent
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasText
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.data.provider.SortDirection as VaadinSortDirection
import de.littletrotter.application.dto.InvoiceAuditActionLine
import de.littletrotter.domain.compliance.InvoiceAuditJournalEntry
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.view.journal.composer.testCtx
import de.littletrotter.ui.view.journal.composer.testLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [JournalPanelParts] — the parts both journal views (per-invoice and global) are built from.
 *  Same no-Karibu shape as [JournalActionGridColumnsTest]: [DataTable] mutates fine unattached,
 *  no MockVaadin session needed. */
class JournalPanelPartsTest {

    private val trEchoKey: (String, List<Any>) -> String =
        { key, args -> if (args.isEmpty()) key else "$key$args" }

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    private fun newGrid(): DataTable<InvoiceAuditActionLine> = DataTable(InvoiceAuditActionLine::class.java, false)

    /** The expand-toggle column's rendered [Button] for [item] — Karibu's own
     *  `getPresentationValue` stringifies a `ComponentRenderer`'s output instead of returning the
     *  [Component] itself, so this reaches the renderer's `createComponent` through reflection:
     *  [column]'s own `renderer` property is already checked-typed as `Renderer<T>`, and only the
     *  reflective invocation (not a Kotlin generic cast) bridges to the concrete
     *  `ComponentRenderer`. */
    private fun <T> componentCellOf(column: com.vaadin.flow.component.grid.Grid.Column<T>, item: T): Component =
        column.renderer.javaClass.getMethod("createComponent", Any::class.java).invoke(column.renderer, item) as Component

    // ── header ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `happy path - header carries the title text and the count chip, class names wired`() {
        val count = Span("3 Einträge")

        val header = JournalPanelParts.header("Journal", count)

        assertTrue(header.classNames.contains("journal-header"))
        assertTrue(count.classNames.containsAll(listOf("status-chip", "status-chip--neutral", "journal-count")))
        val titleBand = header.children.toList().first() as Div
        assertTrue(titleBand.classNames.contains("journal-header__title"))
        val icon = titleBand.children.toList().first()
        assertEquals("true", icon.element.getAttribute("aria-hidden"))
        val h3 = flatten(header).filterIsInstance<HasText>().first { it.text == "Journal" }
        assertEquals("Journal", h3.text)
    }

    @Test
    fun `boundary - the count span passed in is reused as-is, never rebuilt`() {
        val count = Span("0 Einträge")

        val header = JournalPanelParts.header("Journal", count)

        assertTrue(header.children.toList().contains(count), "the same Span instance must be reused")
    }

    // ── configureGrid ────────────────────────────────────────────────────────────────────────

    @Test
    fun `happy path - configureGrid without the invoice column omits the invoice number column`() {
        val grid = newGrid()

        JournalPanelParts.configureGrid(
            grid, JournalTranslations(trEchoKey, { null }), invoiceColumn = false,
            JournalGridCallbacks(composerContext = { testCtx() }, onOpenDialog = {}),
        )

        assertTrue(grid.classNames.contains("journal-grid"))
        assertFalse(grid.isAllRowsVisible)
        val headers = grid.columns.mapNotNull { it.headerText }
        assertFalse(headers.contains("journal.column.invoiceNumber"))
    }

    @Test
    fun `boundary - configureGrid with the invoice column adds exactly one extra column`() {
        val withoutInvoice = newGrid()
        val withInvoice = newGrid()

        JournalPanelParts.configureGrid(
            withoutInvoice, JournalTranslations(trEchoKey, { null }), invoiceColumn = false,
            JournalGridCallbacks(composerContext = { testCtx() }, onOpenDialog = {}),
        )
        JournalPanelParts.configureGrid(
            withInvoice, JournalTranslations(trEchoKey, { null }), invoiceColumn = true,
            JournalGridCallbacks(composerContext = { testCtx() }, onOpenDialog = {}),
        )

        assertEquals(withoutInvoice.columns.size + 1, withInvoice.columns.size)
    }

    @Test
    fun `boundary - the occurredAt column is the only sortable one, sorted server-side by seq`() {
        val grid = newGrid()

        JournalPanelParts.configureGrid(
            grid, JournalTranslations(trEchoKey, { null }), invoiceColumn = false,
            JournalGridCallbacks(composerContext = { testCtx() }, onOpenDialog = {}),
        )

        val occurredAt = grid.getColumnByKey("occurredAt")
        assertTrue(occurredAt != null && occurredAt.isSortable)
        val others = grid.columns.filterNot { it.key == "occurredAt" }
        assertTrue(others.none { it.isSortable })
    }

    @Test
    fun `happy path - configureGrid wires an onOpenDialog callback without throwing`() {
        val grid = newGrid()
        val opened = mutableListOf<InvoiceAuditJournalEntry>()

        JournalPanelParts.configureGrid(
            grid, JournalTranslations(trEchoKey, { null }), invoiceColumn = false,
            JournalGridCallbacks(composerContext = { testCtx() }, onOpenDialog = { opened += it }),
        )

        assertTrue(opened.isEmpty(), "onOpenDialog must not fire just from wiring the grid")
    }

    @Test
    fun `happy path - the item-details renderer builds JournalActionGridColumns technicalRowsDetails`() {
        val grid = newGrid()
        val line = testLine()
        grid.setItems(line)

        JournalPanelParts.configureGrid(
            grid, JournalTranslations(trEchoKey, { null }), invoiceColumn = false,
            JournalGridCallbacks(composerContext = { testCtx() }, onOpenDialog = {}),
        )

        val details = grid._getItemDetailsComponent(line)

        assertEquals(1, details.children.toList().size, "one technical row per entry on the line")
    }

    @Test
    fun `boundary - the expand-toggle column click toggles item-details visibility, on then off`() {
        val grid = newGrid()
        val line = testLine()
        grid.setItems(line)

        JournalPanelParts.configureGrid(
            grid, JournalTranslations(trEchoKey, { null }), invoiceColumn = false,
            JournalGridCallbacks(composerContext = { testCtx() }, onOpenDialog = {}),
        )

        val expandColumn = grid.columns.toList().last()
        assertFalse(grid.isDetailsVisible(line))

        (componentCellOf(expandColumn, line) as Button).click()
        assertTrue(grid.isDetailsVisible(line), "a click on the expand toggle must show item details")

        // The renderer reflects the CURRENT expanded state, so re-render (like a real refresh would).
        (componentCellOf(expandColumn, line) as Button).click()
        assertFalse(grid.isDetailsVisible(line), "a second click must collapse item details again")
    }

    // ── toVaadinSortDirection ────────────────────────────────────────────────────────────────

    @Test
    fun `happy path - toVaadinSortDirection maps ASC to ASCENDING`() {
        assertEquals(VaadinSortDirection.ASCENDING, SortDirection.ASC.toVaadinSortDirection())
    }

    @Test
    fun `boundary - toVaadinSortDirection maps DESC to DESCENDING, never falling back to ASCENDING`() {
        assertEquals(VaadinSortDirection.DESCENDING, SortDirection.DESC.toVaadinSortDirection())
    }
}
