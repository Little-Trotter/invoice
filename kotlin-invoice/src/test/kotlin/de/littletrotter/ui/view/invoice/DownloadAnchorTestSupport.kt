package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.UI
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.server.StreamResourceRegistry
import com.vaadin.flow.server.streams.AbstractDownloadHandler
import com.vaadin.flow.server.streams.DownloadEvent
import com.vaadin.flow.server.streams.DownloadResponse
import com.vaadin.flow.server.streams.InputStreamDownloadCallback
import com.vaadin.flow.server.streams.InputStreamDownloadHandler
import org.mockito.kotlin.mock

/** Runs an [Anchor]'s `DownloadHandler.fromInputStream` callback directly, bypassing
 *  Vaadin's HTTP request machinery (same reflection idiom as [ReceiptDialogDownloadKaribuTest]). */
internal object DownloadAnchorTestSupport {

    fun invoke(anchor: Anchor): DownloadResponse {
        val hrefField = Anchor::class.java.getDeclaredField("href")
        hrefField.isAccessible = true
        val resource = hrefField.get(anchor) as StreamResourceRegistry.ElementStreamResource
        val handler = resource.elementRequestHandler as AbstractDownloadHandler<*>
        val callbackField = InputStreamDownloadHandler::class.java.getDeclaredField("callback")
        callbackField.isAccessible = true
        val callback = callbackField.get(handler) as InputStreamDownloadCallback
        return callback.complete(DownloadEvent(mock(), mock(), mock(), UI.getCurrent().element))
    }

    fun bytes(anchor: Anchor): ByteArray = invoke(anchor).inputStream.use { it.readAllBytes() }
}
