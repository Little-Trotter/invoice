package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10.getNotifications
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.server.StreamResourceRegistry
import com.vaadin.flow.server.streams.AbstractDownloadHandler
import com.vaadin.flow.server.streams.DownloadEvent
import com.vaadin.flow.server.streams.DownloadResponse
import com.vaadin.flow.server.streams.InputStreamDownloadCallback
import com.vaadin.flow.server.streams.InputStreamDownloadHandler
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ReceiptRowDto
import de.littletrotter.application.invoice.port.inbound.AttachReceiptsUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceReceiptQueryPort
import de.littletrotter.domain.exception.ReceiptNotFoundException
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.shared.receipt.InvoiceDocumentBudget
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.OffsetDateTime

// ReceiptTarget.Item(10L) is this file's one fixed target throughout (same as
// ReceiptDialogKaribuTest's own writable-item scenario) — the stubs below match its exact
// argument shape.

/**
 * ST-B2 — [ReceiptDialog.downloadAnchor]/[ReceiptDialog.viewAnchor] had no error handling at all:
 * a receipt whose object is missing in RustFS (or whose S3 call times out) let the store's own
 * exception escape the `DownloadHandler` callback — a stack trace in the log and a broken
 * response, never a notification. Same reflection-based anchor-unwrap idiom
 * [InvoiceListExportDialogTest]/[InvoiceExportDownloadGroupTest] already use to invoke a
 * `DownloadHandler`'s callback directly, bypassing Vaadin's HTTP request machinery entirely.
 */
class ReceiptDialogDownloadKaribuTest {

    private val invoiceReceiptQueryPort: InvoiceReceiptQueryPort = mock()
    private val attachReceiptsUseCase: AttachReceiptsUseCase = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        // ReceiptDialog.open() always fetches the invoice-wide budget once, eagerly (same
        // rationale as ReceiptDialogKaribuTest's own setup()).
        whenever(invoiceReceiptQueryPort.invoiceDocumentBudget(any())).thenReturn(InvoiceDocumentBudget.calculate(emptyList()))
        whenever(invoiceReceiptQueryPort.findReceiptsForItem(10L)).thenReturn(emptyList())
        whenever(invoiceReceiptQueryPort.findAssignableForItem(10L, 0, 50, null)).thenReturn(emptyList())
        whenever(invoiceReceiptQueryPort.countAssignableForItem(10L, null)).thenReturn(0L)
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun receipt(id: Long, name: String = "invoice.pdf") = ReceiptRowDto(
        id = id,
        fileName = name,
        description = "",
        documentDate = null,
        sizeBytes = 100L,
        uploadedAt = OffsetDateTime.now(),
        objectKey = "obj-$id",
    )

    private fun invoice() = InvoiceDetailDto(
        id = 42L, invoiceNumber = "RE-2026-0001", status = InvoiceStatus.DRAFT.ordinal, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = null, items = emptyList(), latestGenerationJob = null,
    )

    /** [ReceiptDialog.open] is called for realism (the dialog's own [Component] is then attached
     *  to the mocked UI, exactly as in production) even though [ReceiptDialog.downloadAnchor]/
     *  [ReceiptDialog.viewAnchor] are called directly afterwards rather than through the grid's
     *  own `ComponentRenderer` — the two build the exact same [Anchor] either way. */
    private fun openDialog(): ReceiptDialog {
        val dialog = ReceiptDialog(invoiceReceiptQueryPort, attachReceiptsUseCase, mock(), invoice(), ReceiptTarget.Item(10L), onChanged = {})
        dialog.open()
        return dialog
    }

    /** Same reflection unwrap [InvoiceListExportDialogTest]/[InvoiceExportDownloadGroupTest] use:
     *  [Anchor.getHref]'s backing field down to the actually registered handler. */
    private fun downloadHandlerOf(anchor: Anchor): AbstractDownloadHandler<*> {
        val hrefField = Anchor::class.java.getDeclaredField("href")
        hrefField.isAccessible = true
        val resource = hrefField.get(anchor) as StreamResourceRegistry.ElementStreamResource
        return resource.elementRequestHandler as AbstractDownloadHandler<*>
    }

    /** Invokes the handler's own callback directly, bypassing Vaadin's HTTP request machinery. A
     *  real owning element (the mocked UI's own root element) is required here, unlike the two
     *  sibling tests this idiom is borrowed from: [ReceiptDialog]'s callback DOES read the
     *  [DownloadEvent] it is given (`event.ui`, to surface the notification — see
     *  `ReceiptDialog.receiptDownloadResponse`'s own KDoc). */
    private fun invokeCallback(anchor: Anchor): DownloadResponse {
        val handler = downloadHandlerOf(anchor)
        val callbackField = InputStreamDownloadHandler::class.java.getDeclaredField("callback")
        callbackField.isAccessible = true
        val callback = callbackField.get(handler) as InputStreamDownloadCallback
        val event = DownloadEvent(mock(), mock(), mock(), UI.getCurrent()!!.element)
        return callback.complete(event)
    }

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    /** [de.littletrotter.ui.notification.UiNotifications.error] builds a custom Div/Span content,
     *  not [Notification.show]'s plain "text" property — same extraction idiom
     *  [InvoiceDetailViewCockpitActionsKaribuTest] already uses for the same reason. */
    private fun notificationText(notification: Notification): String =
        de.littletrotter.ui.toastText(notification)

    @Test
    fun `downloading a receipt whose object is missing shows a notification instead of throwing`() {
        whenever(invoiceReceiptQueryPort.getBytes(1L)).thenThrow(ReceiptNotFoundException(1L))
        val anchor = openDialog().downloadAnchor(receipt(1L))

        val response = invokeCallback(anchor)

        assertFalse(response.hasError(), "an empty response, not an HTTP error status — see receiptDownloadResponse's own KDoc")
        assertEquals(0L, response.contentLength)
        response.inputStream.close()
        assertEquals(1, getNotifications().size, "no exception may escape — the failure must surface as a notification")
        assertEquals(
            UiTranslations.tr("receipt.error.load", "Receipt not found: 1"),
            notificationText(getNotifications().single()),
        )
    }

    @Test
    fun `viewing a receipt whose object is missing shows a notification instead of throwing`() {
        whenever(invoiceReceiptQueryPort.getBytes(2L)).thenThrow(ReceiptNotFoundException(2L))
        val anchor = openDialog().viewAnchor(receipt(2L))

        val response = invokeCallback(anchor)

        assertFalse(response.hasError())
        assertEquals(0L, response.contentLength)
        response.inputStream.close()
        assertEquals(1, getNotifications().size)
    }

    @Test
    fun `any other exception from the service is caught too, not only ReceiptNotFoundException`() {
        // receiptDownloadResponse's own catch stays broad (Exception, not the one type
        // ReceiptPoolService.getBytes currently documents) precisely so a DownloadHandler callback
        // can never let anything through — proven here with an exception type getBytes does not
        // itself throw.
        whenever(invoiceReceiptQueryPort.getBytes(4L)).thenThrow(IllegalStateException("store unreachable"))
        val anchor = openDialog().downloadAnchor(receipt(4L))

        val response = invokeCallback(anchor)

        assertFalse(response.hasError())
        assertEquals(0L, response.contentLength)
        response.inputStream.close()
        assertTrue(getNotifications().isNotEmpty())
    }

    @Test
    fun `a successful download is unaffected by the new error handling`() {
        val bytes = byteArrayOf(1, 2, 3)
        whenever(invoiceReceiptQueryPort.getBytes(3L)).thenReturn(bytes)
        val anchor = openDialog().downloadAnchor(receipt(3L, "hotel.pdf"))

        val response = invokeCallback(anchor)

        assertFalse(response.hasError())
        assertEquals(bytes.size.toLong(), response.contentLength)
        assertArrayEquals(bytes, response.inputStream.readBytes())
        assertTrue(getNotifications().isEmpty())
    }
}
