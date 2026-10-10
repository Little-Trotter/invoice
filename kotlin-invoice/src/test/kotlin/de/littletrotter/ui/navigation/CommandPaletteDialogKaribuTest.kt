package de.littletrotter.ui.navigation

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._fireShortcut
import com.vaadin.flow.component.ClickEvent
import com.vaadin.flow.component.ComponentUtil
import com.vaadin.flow.component.Key
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.ui.view.referencedata.ReferenceDataTab
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify

/**
 * MEN-5 — UI test for [CommandPaletteDialog]: open/type/Enter navigation, recents update, and
 * focus restore on close. Index/filter/recents pure logic is covered without any Vaadin
 * scaffolding in [CommandPaletteIndexTest]; this class only exercises the dialog wiring itself.
 * Keyboard interaction (ArrowUp/Down/Enter) is driven through Karibu-Testing's
 * [_fireShortcut] — the real `Shortcuts.addShortcutListener` registrations, not reflection into
 * the private handler methods — so this class also proves the shortcuts are actually wired up.
 * The `dlg`-field reflection for reading internal state follows
 * [de.littletrotter.ui.view.invoice.ReceiptDialogKaribuTest]'s established idiom.
 */
class CommandPaletteDialogKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun getPrivateField(target: Any, name: String): Any? {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target)
    }

    private fun dialogRootOf(dialog: CommandPaletteDialog): Dialog = getPrivateField(dialog, "dlg") as Dialog

    private fun searchFieldOf(dialog: CommandPaletteDialog): TextField = getPrivateField(dialog, "searchField") as TextField

    private fun resultsContainerOf(dialog: CommandPaletteDialog): Div = getPrivateField(dialog, "resultsContainer") as Div

    private fun resultRowsOf(dialog: CommandPaletteDialog): List<Div> =
        (getPrivateField(dialog, "resultRows") as List<*>).filterIsInstance<Div>()

    private fun firstLabelTextOf(row: Div): String = (row.children.findFirst().get() as Span).text

    @Test
    fun `opening the palette with an empty query shows the full index grouped by section`() {
        val dialog = CommandPaletteDialog()

        dialog.open()

        assertTrue(dialogRootOf(dialog).isOpened)
        assertEquals(buildCommandPaletteIndex().size, resultRowsOf(dialog).size)
    }

    @Test
    fun `typing a query filters the result rows down to the matching entry`() {
        val dialog = CommandPaletteDialog()
        dialog.open()

        searchFieldOf(dialog).value = "kilo"

        val rows = resultRowsOf(dialog)
        assertEquals(1, rows.size)
        assertEquals("Kilometersätze", firstLabelTextOf(rows[0]))
    }

    @Test
    fun `an umlaut-free query still finds the umlauted label`() {
        val dialog = CommandPaletteDialog()
        dialog.open()

        searchFieldOf(dialog).value = "kilometersatze"

        assertEquals(1, resultRowsOf(dialog).size)
    }

    @Test
    fun `a query matching nothing shows the empty state`() {
        val dialog = CommandPaletteDialog()
        dialog.open()

        searchFieldOf(dialog).value = "xyzzy-no-such-entry"

        assertTrue(resultRowsOf(dialog).isEmpty())
    }

    @Test
    fun `Enter on the selected entry navigates to the reference-data deep link and closes the dialog`() {
        val navigatedTo = mutableListOf<String>()
        val dialog = CommandPaletteDialog(navigate = { path -> navigatedTo.add(path) })
        dialog.open()
        searchFieldOf(dialog).value = "kilo"

        searchFieldOf(dialog)._fireShortcut(Key.ENTER)

        assertEquals(listOf("referenzdaten/reisekosten/kilometersaetze"), navigatedTo)
        assertFalse(dialogRootOf(dialog).isOpened, "Enter closes the dialog")
    }

    @Test
    fun `clicking a result row navigates just like Enter does`() {
        val navigatedTo = mutableListOf<String>()
        val dialog = CommandPaletteDialog(navigate = { path -> navigatedTo.add(path) })
        dialog.open()
        searchFieldOf(dialog).value = "kilo"

        val row = resultRowsOf(dialog).single()
        ComponentUtil.fireEvent(row, ClickEvent(row))

        assertEquals(listOf("referenzdaten/reisekosten/kilometersaetze"), navigatedTo)
    }

    @Test
    fun `a visited entry appears first among recents the next time the palette opens`() {
        val dialog = CommandPaletteDialog(navigate = {})
        dialog.open()
        searchFieldOf(dialog).value = "kilo"
        searchFieldOf(dialog)._fireShortcut(Key.ENTER)

        dialog.open()

        val firstRow = resultRowsOf(dialog).first()
        assertEquals("Kilometersätze", firstLabelTextOf(firstRow))
    }

    @Test
    fun `ArrowDown and ArrowUp move the selection with wraparound`() {
        val dialog = CommandPaletteDialog()
        dialog.open()
        val total = resultRowsOf(dialog).size

        searchFieldOf(dialog)._fireShortcut(Key.ARROW_UP)
        assertEquals(total - 1, getPrivateField(dialog, "selectedIndex"))
        assertTrue(resultRowsOf(dialog).last().hasClassName("selected"))

        searchFieldOf(dialog)._fireShortcut(Key.ARROW_DOWN)
        assertEquals(0, getPrivateField(dialog, "selectedIndex"))
        assertTrue(resultRowsOf(dialog).first().hasClassName("selected"))
    }

    @Test
    fun `Esc closes the dialog`() {
        val dialog = CommandPaletteDialog()
        dialog.open()
        assertTrue(dialogRootOf(dialog).isCloseOnEsc, "Esc must be able to close the palette")
    }

    @Test
    fun `focus returns to the triggering component on close`() {
        val dialog = CommandPaletteDialog()
        val trigger = spy(Button())
        UI.getCurrent()!!.add(trigger)

        dialog.open(trigger)
        dialogRootOf(dialog).close()

        verify(trigger).focus()
    }

    @Test
    fun `no restore target is retained once focus has been handed back`() {
        val dialog = CommandPaletteDialog()
        val trigger = Button()
        UI.getCurrent()!!.add(trigger)

        dialog.open(trigger)
        assertEquals(trigger, getPrivateField(dialog, "restoreFocusTarget"))

        dialogRootOf(dialog).close()

        assertNull(getPrivateField(dialog, "restoreFocusTarget"))
    }

    @Test
    fun `opening without a return-focus target closes without error`() {
        val dialog = CommandPaletteDialog()

        dialog.open()
        dialogRootOf(dialog).close()

        assertNull(getPrivateField(dialog, "restoreFocusTarget"))
    }

    // ── TS-8/MEN-5 — itemFilter permission gate ─────────────────────────────────

    @Test
    fun `itemFilter excluding REFERENCE_DATA drops every reference-data entry from the display list`() {
        val dialog = CommandPaletteDialog()
        dialog.itemFilter = { item -> item != NavItem.REFERENCE_DATA }

        dialog.open()

        val rows = resultRowsOf(dialog)
        assertEquals(buildCommandPaletteIndex().size - ReferenceDataTab.entries.size, rows.size)
        assertTrue(rows.none { firstLabelTextOf(it) == "Kilometersätze" }, "no reference-data entry may survive the filter")
    }

    @Test
    fun `itemFilter is re-evaluated on every open, not cached at construction`() {
        val dialog = CommandPaletteDialog()

        // Starts unfiltered.
        dialog.open()
        assertEquals(buildCommandPaletteIndex().size, resultRowsOf(dialog).size)

        // A session's permissions can change between opens (e.g. a fresh navigation re-reads
        // roles) — the palette must reflect the current filter on the next open, not a value
        // baked in when the dialog/index was first built.
        dialog.itemFilter = { item -> item != NavItem.REFERENCE_DATA }
        dialog.open()
        assertEquals(buildCommandPaletteIndex().size - ReferenceDataTab.entries.size, resultRowsOf(dialog).size)

        dialog.itemFilter = { true }
        dialog.open()
        assertEquals(buildCommandPaletteIndex().size, resultRowsOf(dialog).size)
    }

    // ── empty result list keyboard handling ─────────────────────────────────────────────────────

    @Test
    fun `ArrowDown on an empty result list is a no-op, never throws`() {
        val dialog = CommandPaletteDialog()
        dialog.open()
        searchFieldOf(dialog).value = "xyzzy-no-such-entry"

        searchFieldOf(dialog)._fireShortcut(Key.ARROW_DOWN)

        assertEquals(-1, getPrivateField(dialog, "selectedIndex"))
    }

    @Test
    fun `Enter on an empty result list is a no-op, does not navigate or close`() {
        val navigatedTo = mutableListOf<String>()
        val dialog = CommandPaletteDialog(navigate = { path -> navigatedTo.add(path) })
        dialog.open()
        searchFieldOf(dialog).value = "xyzzy-no-such-entry"

        searchFieldOf(dialog)._fireShortcut(Key.ENTER)

        assertTrue(navigatedTo.isEmpty())
        assertTrue(dialogRootOf(dialog).isOpened, "no target selected, so Enter must not close the dialog")
    }

    @Test
    fun `a second visit in the same session finds an already-recorded recents attribute`() {
        val dialog = CommandPaletteDialog(navigate = {})
        dialog.open()
        searchFieldOf(dialog).value = "kilo"
        searchFieldOf(dialog)._fireShortcut(Key.ENTER)

        // Second visit, a different target: VaadinSession already carries a CommandPaletteRecents
        // from the first one (the non-null branch of
        // `session.getAttribute(...) ?: CommandPaletteRecents()`) — must not throw or drop it.
        dialog.open()
        val secondTarget = resultRowsOf(dialog).first { firstLabelTextOf(it) != "Kilometersätze" }
        val secondLabel = firstLabelTextOf(secondTarget)
        ComponentUtil.fireEvent(secondTarget, ClickEvent(secondTarget))

        dialog.open()
        val recents = resultRowsOf(dialog).take(2).map { firstLabelTextOf(it) }
        assertTrue(recents.contains(secondLabel) && recents.contains("Kilometersätze"), "both visits must show up among the recents, got: $recents")
    }

    @Test
    fun `a filtered-out recent target does not resurface once itemFilter excludes it`() {
        val dialog = CommandPaletteDialog(navigate = {})
        dialog.open()
        searchFieldOf(dialog).value = "kilo"
        searchFieldOf(dialog)._fireShortcut(Key.ENTER) // visits the Kilometersätze reference-data tab

        dialog.itemFilter = { item -> item != NavItem.REFERENCE_DATA }
        dialog.open()

        val rows = resultRowsOf(dialog)
        assertTrue(rows.none { firstLabelTextOf(it) == "Kilometersätze" }, "a target the caller lost permission for must not resurface as a recent")
    }

    // ── section header placement right after the recents block ─────────────────────────────────

    @Test
    fun `the section header right after the recents block starts exactly once, not per entry`() {
        // Kilometersätze is a reference-data entry (NavSection.ADMINISTRATION). Visiting it alone
        // makes it the sole recent, so the "remaining" list below the recents block still starts
        // with the INVOICING section (INVOICES/RECEIPTS/PRINT_JOBS/STATISTICS, 4 entries) —
        // this exercises both outcomes of renderResults' `startsNewSection` check: the
        // `index == recentCount` short-circuit that must open a header right after the recents
        // block, and the `previousEntry?.section != entry.section` continuation check that must
        // stay false (no repeated header) for every following same-section entry.
        val dialog = CommandPaletteDialog(navigate = {})
        dialog.open()
        searchFieldOf(dialog).value = "kilo"
        searchFieldOf(dialog)._fireShortcut(Key.ENTER)

        dialog.open()

        val children = resultsContainerOf(dialog).children.toList()
        fun isHeader(index: Int) = children[index].element.classList.contains("command-palette-group-header")

        assertTrue(isHeader(0), "the recents block itself opens with its own 'Recent' header")
        assertFalse(isHeader(1), "the sole recent row must not carry a per-entry header")
        assertTrue(isHeader(2), "the very next section right after the recents block must open its own header")
        for (offset in 3..6) {
            assertFalse(isHeader(offset), "INVOICING entry at position $offset continues the same section, no repeated header")
        }
        assertTrue(isHeader(7), "the section after INVOICING opens its own header")
        assertEquals(
            1 + NavSection.entries.size,
            children.count { it.element.classList.contains("command-palette-group-header") },
            "exactly one header per section plus the recents header, never a duplicate",
        )
    }

    @Test
    fun `boundary - updateSelectionHighlight clears aria-activedescendant when selectedIndex exceeds the row count`() {
        // selectedIndex is only ever set to -1 (empty results) or a value inside
        // resultRows.indices by renderResults/moveSelection — the "positive but out of bounds"
        // outcome of `selectedIndex in resultRows.indices` never occurs through the public API.
        // Setting it directly via reflection proves the defensive bounds check still behaves
        // correctly (no crash, attribute removed) if that invariant is ever violated.
        val dialog = CommandPaletteDialog()
        dialog.open()

        val selectedIndexField = CommandPaletteDialog::class.java.getDeclaredField("selectedIndex").apply { isAccessible = true }
        selectedIndexField.setInt(dialog, resultRowsOf(dialog).size + 5)

        val updateSelectionHighlight =
            CommandPaletteDialog::class.java.getDeclaredMethod("updateSelectionHighlight").apply { isAccessible = true }
        org.junit.jupiter.api.Assertions.assertDoesNotThrow { updateSelectionHighlight.invoke(dialog) }

        assertFalse(searchFieldOf(dialog).element.hasAttribute("aria-activedescendant"))
    }
}
