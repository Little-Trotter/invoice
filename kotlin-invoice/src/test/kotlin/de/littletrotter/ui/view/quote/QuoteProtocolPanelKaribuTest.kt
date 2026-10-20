package de.littletrotter.ui.view.quote

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._get
import com.github.mvysny.kaributesting.v10._getCellComponent
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.GridSortOrder
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.data.provider.Query
import com.vaadin.flow.data.provider.SortDirection
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.inbound.AddQuoteNoteUseCase
import de.littletrotter.application.quote.port.inbound.QuoteLogEntry
import de.littletrotter.application.quote.port.inbound.QuoteLogKind
import de.littletrotter.application.quote.port.inbound.QuoteLogQueryPort
import de.littletrotter.ui.i18n.TranslationProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

/** The offer protocol: same look and behaviour as the invoice journal, fed from the quote log port. */
class QuoteProtocolPanelKaribuTest {

    private val host = object : TranslationProvider {
        private val anchor = Div()
        override fun getComponent() = anchor
    }

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        registerQuoteRoutes()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private val query: QuoteLogQueryPort = mock()
    private val note: AddQuoteNoteUseCase = mock()

    private fun protocol(vararg entries: QuoteLogEntry, mayNote: Boolean = true): QuoteProtocolPanel {
        whenever(query.log(any(), any())).thenReturn(entries.toList())
        return QuoteProtocolPanel(host, query, note).also {
            UI.getCurrent().add(it)
            it.show("q-1", mayNote)
        }
    }

    private val first = QuoteLogEntry(Instant.parse("2026-10-02T12:10:00Z"), "anna", QuoteLogKind.SEND, "Versendet")
    private val second = QuoteLogEntry(Instant.parse("2026-10-01T07:40:00Z"), "bernd", QuoteLogKind.STATUS, "Angelegt")

    private fun rows(panel: QuoteProtocolPanel) = panel.grid.dataProvider.fetch(Query()).toList()

    @Test
    fun `the grid follows the invoice journal styling and only the details column grows`() {
        val panel = protocol(first)

        assertEquals("100%", panel.grid.width)
        assertEquals("100%", panel.width)
        assertTrue(panel.isSpacing)
        assertTrue(panel.grid.classNames.contains("journal-grid"))
        assertFalse(panel.grid.isAllRowsVisible)
        assertEquals(listOf(0, 0, 0, 0, 3), panel.grid.columns.map { it.flexGrow })
        assertEquals(
            listOf("", "Zeitpunkt", "Benutzer", "Vorgang", "Was genau"),
            panel.grid.columns.map { it.headerText.orEmpty() },
        )
        val card = panel.children.findFirst().get() as Div
        assertTrue(card.classNames.containsAll(listOf("ino-card", "journal-invoice-card", "journal-invoice-card--fill")))
    }

    @Test
    fun `each row leads with a tile and shows action and details in the invoice cell classes`() {
        val panel = protocol(first)

        val tile = panel.grid._getCellComponent(0, "tile") as Icon
        assertTrue(tile.classNames.containsAll(listOf("journal-tile", "journal-tile--success")))
        assertEquals("Versand", tile.element.getAttribute("title"))
        assertEquals("true", tile.element.getAttribute("aria-hidden"))
        assertTrue((panel.grid._getCellComponent(0, "action") as Span).classNames.contains("journal-action-label"))
        assertEquals("Versendet", (panel.grid._getCellComponent(0, "what") as Span).text)
        assertTrue((panel.grid._getCellComponent(0, "occurredAt")).classNames.contains("journal-occurred-at"))
    }

    @Test
    fun `every kind has a tile with a known tone`() {
        QuoteLogKind.entries.forEach {
            assertTrue(QuoteLogIcons.modifier(it) in setOf("success", "warning"))
            assertEquals(QuoteLogIcons.icon(it).create().element.getAttribute("icon"), QuoteLogIcons.tile(it, "x").element.getAttribute("icon"))
        }
    }

    @Test
    fun `sorting the time column toggles the order and clearing re-applies the opposite`() {
        val panel = protocol(second, first)
        val time = panel.grid.getColumnByKey("occurredAt")

        panel.grid.sort(listOf(GridSortOrder(time, SortDirection.ASCENDING)))
        assertEquals(listOf(second, first), rows(panel))

        panel.grid.sort(emptyList())
        assertEquals(listOf(first, second), rows(panel))
        assertEquals(SortDirection.DESCENDING, panel.grid.sortOrder.single().direction)

        panel.grid.sort(listOf(GridSortOrder(time, SortDirection.DESCENDING)))
        assertEquals(listOf(first, second), rows(panel))
    }

    @Test
    fun `the viewport fit registers once on attach`() {
        val panel = protocol(first)

        assertEquals(1, panel.viewportFit.registrations)
    }

    @Test
    fun `the band counts the entries and the rows run newest first`() {
        val panel = protocol(second, first)

        assertEquals(listOf(first, second), rows(panel))
        assertEquals("2 Einträge", panel.resultCount.element.getAttribute("aria-label"))
        assertEquals(listOf("2", " Einträge"), panel.resultCount.children.map { it.element.text }.toList())
        assertFalse(panel.emptyState.isVisible)
    }

    @Test
    fun `an empty protocol shows the empty state and a count without number`() {
        val panel = protocol()

        assertTrue(panel.emptyState.isVisible)
        assertTrue(panel.emptyState.classNames.contains("empty-state"))
        assertEquals("Keine Einträge", panel.resultCount.element.getAttribute("aria-label"))
        assertEquals(1, panel.resultCount.children.count())
    }

    @Test
    fun `a note is saved from the dialog and the grid reloads`() {
        val panel = protocol(first)
        panel.noteButton._click()
        val dialog = _get<Dialog>()
        val field = dialog._get<TextArea>()
        val save = dialog._find<Button>().first { it.text == "Speichern" }
        assertFalse(save.isEnabled)

        field.value = "  Rückruf  "
        save._click()

        verify(note).addNote("q-1", "Rückruf")
        verify(query, times(2)).log(any(), any())
        assertFalse(dialog.isOpened)
    }

    @Test
    fun `a refused note keeps the dialog open and the grid untouched`() {
        whenever(note.addNote(any(), any())).thenThrow(QuoteServiceException("Notiz zu lang"))
        val panel = protocol(first)
        panel.noteButton._click()
        val dialog = _get<Dialog>()
        dialog._get<TextArea>().value = "Text"
        dialog._find<Button>().first { it.text == "Speichern" }._click()

        assertTrue(dialog.isOpened)
        verify(query, times(1)).log(any(), any())
    }

    @Test
    fun `cancel closes the note dialog without saving`() {
        val panel = protocol(first)
        panel.noteButton._click()
        val dialog = _get<Dialog>()
        dialog._find<Button>().first { it.text == "Abbrechen" }._click()

        assertFalse(dialog.isOpened)
        verify(note, never()).addNote(any(), any())
    }

    @Test
    fun `readers see no note button`() {
        assertTrue(protocol(first).noteButton.isVisible)
        assertFalse(protocol(first, mayNote = false).noteButton.isVisible)
    }

    @Test
    fun `a panel that was never shown loads nothing and saves nothing`() {
        val panel = QuoteProtocolPanel(host, query, note)

        panel.reload()
        panel.noteButton.click()
        val dialog = _get<Dialog>()
        dialog._get<TextArea>().value = "Text"
        dialog._find<Button>().first { it.text == "Speichern" }._click()

        verify(query, never()).log(any(), any())
        verify(note, never()).addNote(any(), any())
    }
}
