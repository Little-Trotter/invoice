package de.littletrotter.ui.view.quote

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._getCellComponent
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.data.provider.ListDataProvider
import de.littletrotter.application.invoice.port.inbound.ReceiptReadabilityQueryPort
import de.littletrotter.application.quote.QuoteNotFoundException
import de.littletrotter.application.quote.port.inbound.AssignQuoteAttachmentUseCase
import de.littletrotter.application.quote.port.inbound.AttachLibraryDocumentCommand
import de.littletrotter.application.quote.port.inbound.AttachLibraryDocumentUseCase
import de.littletrotter.application.quote.port.inbound.AttachQuoteFileUseCase
import de.littletrotter.application.quote.port.inbound.AttachmentLibraryQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteAttachment
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentContent
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentKind
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentQueryPort
import de.littletrotter.application.quote.port.inbound.RemoveQuoteAttachmentUseCase
import de.littletrotter.application.quote.port.inbound.ReplaceLibraryVersionCommand
import java.time.Instant
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class QuoteAttachmentsTabKaribuTest {

    private val query: QuoteAttachmentQueryPort = mock()
    private val attach: AttachQuoteFileUseCase = mock()
    private val remove: RemoveQuoteAttachmentUseCase = mock()
    private val assign: AssignQuoteAttachmentUseCase = mock()
    private val library: AttachmentLibraryQueryPort = mock()
    private val attachLibrary: AttachLibraryDocumentUseCase = mock()

    private val agb1 = libraryVersion("v-1", 1)
    private val agb2 = libraryVersion("v-2", 2)
    private val agb = libraryEntry("l-1", "AGB", agb1, agb2)
    private val sheet = libraryEntry("l-2", "Datenblatt", libraryVersion("v-3", 1, "datenblatt.pdf"))
    private val old = libraryEntry("l-3", "Alt", libraryVersion("v-4", 1), archived = true)

    private fun upload(id: String) =
        QuoteAttachment(id, QuoteAttachmentKind.INTERNAL, "$id.xlsx", "application/vnd.ms-excel", 1024, "a".repeat(64), Instant.parse("2026-10-01T09:00:00Z"))

    private fun pooled(id: String, newer: Boolean) =
        QuoteAttachment(
            id, QuoteAttachmentKind.CUSTOMER, "agb-1.pdf", "application/pdf", 2048, "b".repeat(64), Instant.parse("2026-10-01T09:00:00Z"),
            libraryVersionId = "v-1", libraryTitle = "AGB", versionNo = 1, newerVersionAvailable = newer,
        )

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        whenever(library.entries()).thenReturn(listOf(agb, sheet, old))
        whenever(query.attachments("q-1")).thenReturn(listOf(upload("f-1"), pooled("a-2", newer = true)))
        whenever(query.content("q-1", "f-1")).thenReturn(QuoteAttachmentContent("f-1.xlsx", "application/octet-stream", byteArrayOf(1)))
        whenever(attachLibrary.attach(any())).thenReturn("a-9")
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun tab(): QuoteAttachmentsTab =
        QuoteAttachmentsTab(testHost(), QuoteAttachmentsTabPorts(query, attach, remove, assign, library, attachLibrary, ReceiptReadabilityQueryPort { emptyList() })).also {
            UI.getCurrent().add(it)
            it.bind("q-1", quoteDetail().items)
        }

    @Test
    fun `three sub-tabs with the assigned count in the first`() {
        val tab = tab()

        assertEquals("Zugeordnet (2)", tab.assignedTab.label)
        assertEquals("Angebotsbelege", tab.poolTab.label)
        assertEquals("Hochladen", tab.uploadTab.label)
        assertEquals(3, tab.subTabs.tabCount)
    }

    @Test
    fun `the assigned list shows the origin of each file`() {
        val tab = tab()

        assertEquals(2, (tab.grid.dataProvider as ListDataProvider<QuoteAttachment>).items.size)
        val origins = listOf(upload("f-1"), pooled("a-2", newer = true)).map { attachmentOriginText(testHost(), it) }
        assertEquals(listOf("Upload", "AGB · Fassung 1 · neuere Fassung vorhanden"), origins)
    }

    @Test
    fun `a pool file without a title still names the pool and its version`() {
        assertEquals("Angebotsbelege, Fassung 1", attachmentOriginText(testHost(), pooled("a-3", newer = false).copy(libraryTitle = " ")))
    }

    @Test
    fun `the file column carries the origin as its second line instead of separate columns`() {
        val tab = tab()

        val lines = tab.grid._getCellComponent(1, "name")._find<com.vaadin.flow.component.html.Span>().map { it.text }

        assertEquals(listOf("agb-1.pdf", "AGB · Fassung 1 · neuere Fassung vorhanden"), lines)
        assertEquals(null, tab.grid.getColumnByKey("origin"))
        assertEquals(null, tab.grid.getColumnByKey("description"))
    }

    @Test
    fun `each file offers view and download like a receipt`() {
        val tab = tab()

        val anchors = tab.grid._getCellComponent(0, "actions")._find<Anchor>()

        assertEquals(2, anchors.size)
        assertEquals(listOf("_blank", null), anchors.map { it.element.getAttribute("target") })
        assertTrue(anchors[1].element.hasAttribute("download"))
        assertEquals(listOf("Anzeigen", "Herunterladen"), anchors.map { it.element.getAttribute("title") })
    }

    @Test
    fun `the pool lists only entries that are not archived`() {
        val tab = tab()

        assertEquals(listOf(agb, sheet), (tab.pool.grid.dataProvider as ListDataProvider<*>).items.toList())
    }

    @Test
    fun `several pool entries are assigned at once in their current version`() {
        val tab = tab()
        tab.subTabs.selectedTab = tab.poolTab
        tab.pool.grid.select(agb)
        tab.pool.grid.select(sheet)

        tab.assignFromPool._click()

        verify(attachLibrary).attach(AttachLibraryDocumentCommand("q-1", "v-2", null))
        verify(attachLibrary).attach(AttachLibraryDocumentCommand("q-1", "v-3", null))
        assertEquals(tab.assignedTab, tab.subTabs.selectedTab)
    }

    @Test
    fun `the chosen line is passed to the pool assignment`() {
        val tab = tab()
        tab.subTabs.selectedTab = tab.poolTab
        tab.pool.grid.select(sheet)
        tab.poolItemBox.value = quoteDetail().items.first { it.position == 2 }

        tab.assignFromPool._click()

        verify(attachLibrary).attach(AttachLibraryDocumentCommand("q-1", "v-3", "i-2"))
    }

    @Test
    fun `assigning without a selection does nothing`() {
        val tab = tab()
        tab.subTabs.selectedTab = tab.poolTab

        tab.assignFromPool._click()

        verify(attachLibrary, never()).attach(any())
    }

    @Test
    fun `the update action appears only for a pool file with a newer version`() {
        val tab = tab()

        fun updateButtons(row: Int) = tab.grid._getCellComponent(row, "actions")._find<Button>().filter { it.element.getAttribute("aria-label") == "Fassung aktualisieren" }

        assertTrue(updateButtons(0).isEmpty())
        assertEquals(1, updateButtons(1).size)
    }

    @Test
    fun `no update action when the pool holds no newer version`() {
        whenever(query.attachments("q-1")).thenReturn(listOf(pooled("a-2", newer = false)))
        val tab = tab()

        val labels = tab.grid._getCellComponent(0, "actions")._find<Button>().map { it.element.getAttribute("aria-label") }

        assertFalse("Fassung aktualisieren" in labels)
    }

    @Test
    fun `updating swaps to the current version of the entry the file came from`() {
        val tab = tab()
        val button = tab.grid._getCellComponent(1, "actions")._find<Button>().first { it.element.getAttribute("aria-label") == "Fassung aktualisieren" }

        button._click()

        verify(attachLibrary).replaceVersion(ReplaceLibraryVersionCommand("q-1", "a-2", "v-2"))
    }

    @Test
    fun `updating a file whose entry is gone from the pool does nothing`() {
        whenever(library.entries()).thenReturn(emptyList())
        val tab = tab()

        tab.updateVersion(pooled("a-2", newer = true))

        verify(attachLibrary, never()).replaceVersion(any())
    }

    @Test
    fun `a refused swap leaves the list as it is`() {
        whenever(attachLibrary.replaceVersion(any())).thenThrow(QuoteNotFoundException("Fassung fehlt"))
        val tab = tab()

        tab.updateVersion(pooled("a-2", newer = true))

        verify(attachLibrary).replaceVersion(any())
    }

    @Test
    fun `a file that vanished downloads as empty content`() {
        whenever(query.content("q-1", "f-1")).thenThrow(QuoteNotFoundException("Beleg nicht gefunden"))
        val tab = tab()

        val response = tab.downloadResponse(upload("f-1"))

        assertFalse(response.hasError())
        assertEquals(0L, response.contentLength)
    }
}
