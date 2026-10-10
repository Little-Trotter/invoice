package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.server.streams.DownloadEvent
import com.vaadin.flow.server.streams.DownloadResponse
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentContent
import de.littletrotter.ui.component.emptyFileResponse
import de.littletrotter.ui.component.fileDownloadAnchor
import de.littletrotter.ui.component.fileViewAnchor
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.notification.UiNotifications
import org.jboss.logging.Logger
import java.io.ByteArrayInputStream

private const val FALLBACK_MIME_TYPE = "application/octet-stream"
private val log: Logger = Logger.getLogger("de.littletrotter.ui.view.quote.QuoteFileLinks")

/**
 * The bytes behind one offer file, fetched only when the browser asks for them so listing never
 * moves file content. A file the offer component no longer knows or cannot deliver is reported as a
 * notification in the originating tab and answered with empty content: the inline view opens in a
 * fresh tab where an HTTP error page would only be noise, the same treatment as invoice receipts.
 */
internal fun quoteFileResponse(event: DownloadEvent?, fileName: String, load: () -> QuoteAttachmentContent): DownloadResponse =
    try {
        val content = load()
        DownloadResponse(ByteArrayInputStream(content.bytes), content.fileName, content.mimeType, content.bytes.size.toLong())
    } catch (e: QuoteServiceException) {
        log.warn("Offer file download failed: ${e.message}")
        event?.ui?.access { UiNotifications.error("${e.message}") }
        emptyFileResponse(fileName, FALLBACK_MIME_TYPE)
    }

/**
 * View (eye, inline in a new tab) and download (arrow) of one offer file, built exactly like the
 * links of an invoice receipt: same symbols, same tooltips, same failure handling.
 */
internal fun quoteFileActions(host: TranslationProvider, fileName: String, load: () -> QuoteAttachmentContent): Component {
    val view = fileViewAnchor(host.tr("receipt.action.view")) { event -> quoteFileResponse(event, fileName, load) }
    val download = fileDownloadAnchor(fileName, host.tr("receipt.action.download")) { event -> quoteFileResponse(event, fileName, load) }
    return HorizontalLayout(view, download).apply {
        isPadding = false
        isSpacing = true
    }
}
