package de.littletrotter.ui.view.quote

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._get
import com.github.mvysny.kaributesting.v10._getCellComponent
import com.github.mvysny.kaributesting.v10._getFormattedRow
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.data.provider.ListDataProvider
import de.littletrotter.application.invoice.port.inbound.ReceiptReadabilityQueryPort
import de.littletrotter.application.quote.QuoteAvailability
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.inbound.AttachmentLibraryEntry
import de.littletrotter.application.quote.port.inbound.AttachmentLibraryFile
import de.littletrotter.application.quote.port.inbound.AttachmentLibraryQueryPort
import de.littletrotter.application.quote.port.inbound.MaintainAttachmentLibraryUseCase
import de.littletrotter.application.quote.port.inbound.NewAttachmentLibraryEntry
import de.littletrotter.application.quote.port.inbound.StoredQuoteFile
import de.littletrotter.application.security.AppPermission
import de.littletrotter.ui.view.invoice.InvoiceListView
import java.time.LocalDate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class QuoteReceiptPoolViewKaribuTest {

    private val query: AttachmentLibraryQueryPort = mock()
    private val maintain: MaintainAttachmentLibraryUseCase = mock()

    private val agb = libraryEntry("l-1", "AGB", libraryVersion("v-1", 1), libraryVersion("v-2", 2), usedBy = 3)
    private val old = libraryEntry("l-2", "Alt", libraryVersion("v-3", 1), archived = true)

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        registerQuoteRoutes()
        whenever(query.entries()).thenReturn(listOf(agb, old))
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun enter(enabled: Boolean = true, vararg granted: AppPermission = ALL_QUOTE_PERMISSIONS): Pair<QuoteReceiptPoolView, com.vaadin.flow.router.BeforeEnterEvent> {
        val view = QuoteReceiptPoolView(query, maintain, ReceiptReadabilityQueryPort { emptyList() }, QuoteAvailability(enabled), rolesWith(*granted))
        val event = quoteEvent(null, "quote-receipt-pool")
        UI.getCurrent().add(view)
        view.beforeEnter(event)
        return view to event
    }

    private fun items(view: QuoteReceiptPoolView) = (view.grid.dataProvider as ListDataProvider<AttachmentLibraryEntry>).items.toList()

    private fun actionLabels(view: QuoteReceiptPoolView, row: Int) =
        view.grid._getCellComponent(row, "actions")._find<Button>().map { it.element.getAttribute("aria-label") }

    @Test
    fun `without the component or the permission the view forwards to the invoice list`() {
        val (_, off) = enter(enabled = false)
        verify(off).forwardTo(InvoiceListView::class.java)

        val (_, denied) = enter(true, AppPermission.QUOTE_EDIT)
        verify(denied).forwardTo(InvoiceListView::class.java)
    }

    @Test
    fun `the list shows entries with current version and usage, archived ones only on request`() {
        val (view, _) = enter()

        assertEquals(listOf(agb), items(view))
        val row = view.grid._getFormattedRow(0)
        assertTrue("Fassung 2" in row, "$row")
        assertTrue("3" in row, "$row")

        view.showArchived.value = true

        assertEquals(listOf(agb, old), items(view))
    }

    @Test
    fun `an empty pool shows its empty state`() {
        whenever(query.entries()).thenReturn(emptyList())

        val (view, _) = enter()

        assertTrue(view.emptyState.isVisible)
        assertFalse(view.grid.isVisible)
    }

    @Test
    fun `an unreachable component leaves the list empty`() {
        whenever(query.entries()).thenThrow(QuoteServiceException("nicht erreichbar"))

        val (view, _) = enter()

        assertTrue(view.emptyState.isVisible)
    }

    @Test
    fun `a row offers view and download of the current version`() {
        val (view, _) = enter()

        val anchors = view.grid._getCellComponent(0, "actions")._find<Anchor>()

        assertEquals(listOf("Anzeigen", "Herunterladen"), anchors.map { it.element.getAttribute("title") })
    }

    @Test
    fun `archiving is offered only with its own right and only for active entries`() {
        val (view, _) = enter()
        assertTrue("Archivieren" in actionLabels(view, 0))

        view.showArchived.value = true
        assertFalse("Archivieren" in actionLabels(view, 1))
        assertFalse("Neue Fassung hochladen" in actionLabels(view, 1))
    }

    @Test
    fun `without the archive right the archive action is hidden`() {
        val (view, _) = enter(true, AppPermission.QUOTE_VIEW, AppPermission.QUOTE_LIBRARY_EDIT)

        assertFalse("Archivieren" in actionLabels(view, 0))
        assertTrue("Neue Fassung hochladen" in actionLabels(view, 0))
        assertTrue(view.newEntry.isVisible)
    }

    @Test
    fun `a viewer without maintenance rights can only look`() {
        val (view, _) = enter(true, AppPermission.QUOTE_VIEW)

        assertEquals(listOf("Fassungen anzeigen"), actionLabels(view, 0))
        assertFalse(view.newEntry.isVisible)
    }

    @Test
    fun `the confirmed archive action archives the entry and reloads`() {
        val (view, _) = enter()
        view.grid._getCellComponent(0, "actions")._find<Button>().first { it.element.getAttribute("aria-label") == "Archivieren" }._click()

        UI.getCurrent()._get<ConfirmDialog>().let { dialog ->
            assertTrue(dialog.isOpened)
        }
        view.archive(agb)

        verify(maintain).archive("l-1")
    }

    @Test
    fun `a refused archive is reported and nothing else happens`() {
        whenever(maintain.archive("l-1")).thenThrow(QuoteServiceException("nicht erlaubt"))
        val (view, _) = enter()

        view.archive(agb)

        verify(maintain).archive("l-1")
    }

    @Test
    fun `the versions dialog lists every version newest first`() {
        val (view, _) = enter()

        view.grid._getCellComponent(0, "actions")._find<Button>().first { it.element.getAttribute("aria-label") == "Fassungen anzeigen" }._click()

        val dialog = UI.getCurrent()._get<Dialog>()
        val versions = dialog._get<com.vaadin.flow.component.grid.Grid<*>>()
        assertEquals(listOf(2, 1), (versions.dataProvider as ListDataProvider<*>).items.map { (it as de.littletrotter.application.quote.port.inbound.AttachmentLibraryVersion).versionNo })
    }

    @Test
    fun `a new version dialog uploads the file as the next version`() {
        whenever(maintain.addVersion(any(), any())).thenReturn(StoredQuoteFile("v-9"))
        val (view, _) = enter()
        view.grid._getCellComponent(0, "actions")._find<Button>().first { it.element.getAttribute("aria-label") == "Neue Fassung hochladen" }._click()

        val dialog = UI.getCurrent()._get<QuoteReceiptPoolUploadDialog>()
        dialog.onFile("agb3.pdf", "application/pdf", byteArrayOf(7))
        dialog.validFrom.value = LocalDate.of(2026, 12, 1)
        dialog.save()

        val file = argumentCaptor<AttachmentLibraryFile>().also { verify(maintain).addVersion(org.mockito.kotlin.eq("l-1"), it.capture()) }.firstValue
        assertEquals("agb3.pdf", file.fileName)
        assertEquals(LocalDate.of(2026, 12, 1), file.validFrom)
    }

    @Test
    fun `a new entry needs title and description before it is created`() {
        whenever(maintain.create(any())).thenReturn(StoredQuoteFile("l-9"))
        val (view, _) = enter()
        view.newEntry._click()
        val dialog = UI.getCurrent()._get<QuoteReceiptPoolUploadDialog>()
        dialog.onFile("datenblatt.pdf", null, byteArrayOf(1))

        dialog.save()
        verify(maintain, never()).create(any())
        assertTrue(dialog.title.isInvalid)

        dialog.title.value = "Datenblatt"
        dialog.description.value = "Technische Daten"
        dialog.save()

        val entry = argumentCaptor<NewAttachmentLibraryEntry>().also { verify(maintain).create(it.capture()) }.firstValue
        assertEquals("Datenblatt", entry.title)
        assertEquals("application/octet-stream", entry.file.mimeType)
    }

    @Test
    fun `saving without a picked file does nothing`() {
        val (view, _) = enter()
        view.newEntry._click()
        val dialog = UI.getCurrent()._get<QuoteReceiptPoolUploadDialog>()

        dialog.save()

        verify(maintain, never()).create(any())
        assertFalse(dialog.save.isEnabled)
    }
}
