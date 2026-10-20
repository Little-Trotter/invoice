package de.littletrotter.ui.view.invoice

import de.littletrotter.application.invoice.port.inbound.UploadReceiptUseCase
import de.littletrotter.application.invoice.port.inbound.ReceiptPoolQueryPort
import de.littletrotter.application.invoice.port.inbound.EditReceiptDetailsUseCase
import de.littletrotter.application.invoice.port.inbound.DeleteReceiptUseCase
import de.littletrotter.application.invoice.port.inbound.ReceiptView
import de.littletrotter.testing.receiptView
import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._find
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import de.littletrotter.domain.exception.ReceiptNotFoundException
import com.vaadin.flow.component.UI
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.OffsetDateTime

/** [ReceiptPoolView]'s download/view anchors stream the stored bytes, and the edit
 *  dialog's save edge cases (id-less receipt, exception without a message). */
class ReceiptPoolViewDownloadKaribuTest {

    private val uploadReceiptUseCase: UploadReceiptUseCase = mock()
    private val receiptPoolQueryPort: ReceiptPoolQueryPort = mock()
    private val editReceiptDetailsUseCase: EditReceiptDetailsUseCase = mock()
    private val deleteReceiptUseCase: DeleteReceiptUseCase = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        MockVaadin.tearDown()
    }

    private fun receipt(id: Long?, name: String = "beleg.pdf") = receiptView {
        this.id = id
        fileName = name
        sizeBytes = 3L
        uploadedAt = OffsetDateTime.now()
        description = "Taxi"
        documentDate = LocalDate.of(2026, 8, 15)
    }

    private fun anchors(view: ReceiptPoolView, receipt: ReceiptView): List<Anchor> {
        val m = ReceiptPoolView::class.java.getDeclaredMethod("buildActionColumn", ReceiptView::class.java)
        m.isAccessible = true
        return (m.invoke(view, receipt) as HorizontalLayout).children.toList().filterIsInstance<Anchor>()
    }

    private fun openEditDialog(view: ReceiptPoolView, receipt: ReceiptView) {
        val m = ReceiptPoolView::class.java.getDeclaredMethod("openEditDialog", ReceiptView::class.java)
        m.isAccessible = true
        m.invoke(view, receipt)
    }

    private fun saveButton(view: ReceiptPoolView) = _find<Button>().single { it.text == UiTranslations.tr(view, "app.save") }

    @Test
    fun `happy path - the download anchor streams the stored PDF under the receipt file name`() {
        whenever(receiptPoolQueryPort.getBytes(7L)).thenReturn(byteArrayOf(1, 2, 3))
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())

        val response = DownloadAnchorTestSupport.invoke(anchors(view, receipt(7L)).first())

        assertEquals("beleg.pdf", response.fileName)
        assertEquals("application/pdf", response.contentType)
        assertEquals(3L, response.contentLength)
        assertArrayEquals(byteArrayOf(1, 2, 3), response.inputStream.use { it.readAllBytes() })
    }

    @Test
    fun `boundary - the inline view anchor streams the same bytes as the download anchor`() {
        whenever(receiptPoolQueryPort.getBytes(7L)).thenReturn(byteArrayOf(9, 8))
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())
        val links = anchors(view, receipt(7L))

        assertEquals(2, links.size, "download and inline view")
        assertArrayEquals(byteArrayOf(9, 8), DownloadAnchorTestSupport.bytes(links[1]))
    }

    @Test
    fun `boundary - an empty stored receipt streams zero bytes with a zero content length`() {
        whenever(receiptPoolQueryPort.getBytes(7L)).thenReturn(ByteArray(0))
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())

        val response = DownloadAnchorTestSupport.invoke(anchors(view, receipt(7L)).first())

        assertEquals(0L, response.contentLength)
    }

    @Test
    fun `boundary - saving the edit dialog of an id-less receipt changes nothing`() {
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())

        openEditDialog(view, receipt(null))
        saveButton(view)._click()

        verify(editReceiptDetailsUseCase, never()).updateDetails(any(), any(), any())
        assertTrue(_find<Dialog>().isNotEmpty(), "the dialog stays open")
    }

    @Test
    fun `boundary - a failing save without an exception message keeps the dialog open`() {
        whenever(editReceiptDetailsUseCase.updateDetails(1L, "Taxi", LocalDate.of(2026, 8, 15))).thenThrow(RuntimeException())
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())

        openEditDialog(view, receipt(1L))
        saveButton(view)._click()

        assertTrue(_find<Dialog>().isNotEmpty(), "the edit dialog stays open when the save fails")
    }

    @Test
    fun `edge - a receipt whose stored object is missing answers 404, notifies and does not throw`() {
        whenever(receiptPoolQueryPort.getBytes(7L)).thenThrow(ReceiptNotFoundException(7L))
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())
        val links = anchors(view, receipt(7L))

        val responses = links.map { DownloadAnchorTestSupport.invoke(it) }

        responses.forEach { assertEquals(404, it.error, "no streaming, a clean 404") }
        assertTrue(
            _find<com.vaadin.flow.component.notification.Notification>().isNotEmpty() ||
                UI.getCurrent().children.anyMatch { it is com.vaadin.flow.component.notification.Notification },
            "the user is told the receipt is no longer available",
        )
    }

    @Test
    fun `edge - a store failure other than not-found is handled the same way`() {
        whenever(receiptPoolQueryPort.getBytes(7L)).thenThrow(IllegalStateException("store down"))
        val view = ReceiptPoolView(uploadReceiptUseCase, receiptPoolQueryPort, editReceiptDetailsUseCase, deleteReceiptUseCase, mock())

        val response = DownloadAnchorTestSupport.invoke(anchors(view, receipt(7L)).first())

        assertEquals(404, response.error)
    }
}
