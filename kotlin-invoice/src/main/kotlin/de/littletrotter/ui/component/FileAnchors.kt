package de.littletrotter.ui.component

import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.server.streams.DownloadEvent
import com.vaadin.flow.server.streams.DownloadHandler
import com.vaadin.flow.server.streams.DownloadResponse
import java.io.ByteArrayInputStream

private const val TITLE_ATTR = "title"
private const val ARIA_LABEL_ATTR = "aria-label"

/** Save-to-disk link behind the download symbol; [tooltip] is both mouse-over text and accessible name. */
fun fileDownloadAnchor(fileName: String, tooltip: String, handler: (DownloadEvent) -> DownloadResponse): Anchor =
    Anchor(DownloadHandler.fromInputStream { event -> handler(event) }, "").apply {
        element.setAttribute("download", fileName)
        element.setAttribute(TITLE_ATTR, tooltip)
        element.setAttribute(ARIA_LABEL_ATTR, tooltip)
        add(ActionIcons.DOWNLOAD.create())
    }

/** Excel download: the [AppButtons.spreadsheetDownload] icon inside a save-to-disk link; [buttonId] stays addressable. */
fun spreadsheetDownloadAnchor(buttonId: String, handler: (DownloadEvent) -> DownloadResponse): Anchor =
    Anchor(DownloadHandler.fromInputStream { event -> handler(event) }, "").apply {
        element.setAttribute("download", true)
        addClassName("spreadsheet-download")
        add(AppButtons.spreadsheetDownload().apply { setId(buttonId) })
    }

/** Link behind the eye symbol: the file opens inline in a new browser tab instead of being saved. */
fun fileViewAnchor(tooltip: String, handler: (DownloadEvent) -> DownloadResponse): Anchor =
    Anchor(DownloadHandler.fromInputStream { event -> handler(event) }.inline(), "").apply {
        element.setAttribute("target", "_blank")
        element.setAttribute(TITLE_ATTR, tooltip)
        element.setAttribute(ARIA_LABEL_ATTR, tooltip)
        add(ActionIcons.VIEW.create())
    }

/**
 * The answer for a file that could not be loaded. Empty content rather than an HTTP error status:
 * the inline view opens in a fresh tab where an error page would only be noise, and the reason is
 * already shown as a notification in the originating tab.
 */
fun emptyFileResponse(fileName: String, mimeType: String): DownloadResponse =
    DownloadResponse(ByteArrayInputStream(ByteArray(0)), fileName, mimeType, 0)
