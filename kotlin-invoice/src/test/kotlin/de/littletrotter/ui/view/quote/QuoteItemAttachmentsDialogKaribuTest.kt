package de.littletrotter.ui.view.quote

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._getCellComponent
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.data.provider.ListDataProvider
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.inbound.AssignQuoteAttachmentCommand
import de.littletrotter.application.quote.port.inbound.AssignQuoteAttachmentUseCase
import de.littletrotter.application.quote.port.inbound.AttachQuoteFileUseCase
import de.littletrotter.application.quote.port.inbound.QuoteAttachment
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentContent
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentKind
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentUpload
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
import java.time.Instant

class QuoteItemAttachmentsDialogKaribuTest {

    private val query: QuoteAttachmentQueryPort = mock()
    private val attach: AttachQuoteFileUseCase = mock()
    private val assign: AssignQuoteAttachmentUseCase = mock()
    private var changes = 0

    private fun file(id: String, itemId: String?) =
        QuoteAttachment(id, QuoteAttachmentKind.CUSTOMER, "$id.pdf", "application/pdf", 2048, "a".repeat(64), Instant.parse("2026-10-01T09:00:00Z"), itemId, itemId?.let { 1 })

    private val mine = file("f-1", "i-1")
    private val other = file("f-2", "i-2")
    private val free = file("f-3", null)

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        whenever(query.attachments("q-1")).thenReturn(listOf(mine, other, free))
        whenever(query.content("q-1", "f-1")).thenReturn(QuoteAttachmentContent("f-1.pdf", "application/pdf", byteArrayOf(1, 2)))
        whenever(attach.attach(any(), any())).thenReturn("f-9")
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun dialog(readOnly: Boolean = false) =
        QuoteItemAttachmentsDialog(
            testHost(), "q-1", quoteItem(1),
            if (readOnly) QuoteItemAttachmentPorts(query) else QuoteItemAttachmentPorts(query, attach, assign),
        ) { changes++ }.also { it.open() }

    @Test
    fun `the list shows only the files of this line and the choice only the unassigned ones`() {
        val dialog = dialog()

        assertTrue(dialog.dialog.isOpened)
        assertEquals("Belege zu Position 1", dialog.dialog.headerTitle)
        assertEquals(listOf(mine), (dialog.grid.dataProvider as ListDataProvider<QuoteAttachment>).items.toList())
        assertEquals(listOf(free), dialog.assignBox.listDataView.items.toList())
        assertFalse(dialog.emptyState.isVisible)
        assertEquals("Aus Angebot zuordnen", dialog.assignBox.label)
    }

    @Test
    fun `a line without files shows the empty state`() {
        whenever(query.attachments("q-1")).thenReturn(listOf(other))
        val dialog = dialog()

        assertTrue(dialog.emptyState.isVisible)
    }

    @Test
    fun `the footer holds the choice the upload and close in this order`() {
        val dialog = dialog()

        val footer = footerComponents(dialog.dialog).single()
        assertEquals(listOf(dialog.assignBox, dialog.upload, dialog.closeButton), footer.children.toList())
        dialog.closeButton._click()
        assertFalse(dialog.dialog.isOpened)
    }

    @Test
    fun `choosing an unassigned file links it to the line and refreshes`() {
        val dialog = dialog()
        dialog.assignBox.value = free

        val command = argumentCaptor<AssignQuoteAttachmentCommand>().also { verify(assign).assign(it.capture()) }.firstValue
        assertEquals(AssignQuoteAttachmentCommand("q-1", "f-3", "i-1"), command)
        assertEquals(1, changes)
    }

    @Test
    fun `releasing a file drops its link to the line`() {
        val dialog = dialog()
        val actions = dialog.grid._getCellComponent(0, "actions")
        actions._find<Button>().single { it.element.getAttribute("aria-label") == "Von der Position lösen" }._click()

        verify(assign).assign(AssignQuoteAttachmentCommand("q-1", "f-1", null))
        assertEquals(1, changes)
    }

    @Test
    fun `an upload is stored with this lines id for the customer`() {
        val dialog = dialog()
        dialog.onUploaded("zeichnung.pdf", "application/pdf", byteArrayOf(7))

        val upload = argumentCaptor<QuoteAttachmentUpload>().also { verify(attach).attach(org.mockito.kotlin.eq("q-1"), it.capture()) }.firstValue
        assertEquals("i-1", upload.itemId)
        assertEquals(QuoteAttachmentKind.CUSTOMER, upload.kind)
        assertEquals("zeichnung.pdf", upload.fileName)
        assertEquals(1, changes)
        assertTrue(notificationTexts().any { it.contains("zeichnung.pdf") })
    }

    @Test
    fun `an upload without a content type falls back to a binary type`() {
        val dialog = dialog()
        dialog.onUploaded("daten.bin", null, byteArrayOf(7))

        val upload = argumentCaptor<QuoteAttachmentUpload>().also { verify(attach).attach(any(), it.capture()) }.firstValue
        assertEquals("application/octet-stream", upload.mimeType)
    }

    @Test
    fun `a refusal is shown and counts do not change`() {
        whenever(assign.assign(any())).thenThrow(QuoteServiceException("Angebot gesperrt"))
        whenever(attach.attach(any(), any())).thenThrow(QuoteServiceException("Datei zu groß"))
        val dialog = dialog()
        dialog.assignBox.value = free
        dialog.onUploaded("a.pdf", "application/pdf", byteArrayOf(1))

        assertEquals(0, changes)
        // Single toast slot: the second refusal replaces the first, so only the latest is visible.
        assertEquals(listOf("Datei zu groß"), notificationTexts().map { it.trim() })
    }

    @Test
    fun `read only the dialog offers download and close only`() {
        val dialog = dialog(readOnly = true)

        assertTrue(footerComponents(dialog.dialog).single().children.toList() == listOf(dialog.closeButton))
        val actions = dialog.grid._getCellComponent(0, "actions")
        assertTrue(actions._find<Button>().none { it.element.getAttribute("aria-label") == "Von der Position lösen" })
        assertTrue(actions._find<com.vaadin.flow.component.html.Anchor>().isNotEmpty())
        dialog.assign(mine, null)
        dialog.onUploaded("a.pdf", null, byteArrayOf(1))
        verify(assign, never()).assign(any())
        verify(attach, never()).attach(any(), any())
    }
}
