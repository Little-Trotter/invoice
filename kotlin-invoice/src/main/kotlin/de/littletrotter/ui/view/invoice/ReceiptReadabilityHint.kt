package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.server.streams.DownloadHandler
import com.vaadin.flow.server.streams.DownloadResponse
import de.littletrotter.application.invoice.port.inbound.HardToReadPage
import de.littletrotter.application.invoice.port.inbound.ReceiptReadabilityCheckException
import de.littletrotter.application.invoice.port.inbound.ReceiptReadabilityQueryPort
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.AppDialog
import de.littletrotter.ui.i18n.UiTranslations
import org.jboss.logging.Logger
import java.io.ByteArrayInputStream

private val log = Logger.getLogger("de.littletrotter.ui.view.invoice.ReceiptReadabilityHint")

/**
 * Upload-time readability hint, run before the size decision of [offerReceiptUpload]: when the
 * picked PDF has pages that look too coarse or blurred to read in print, [ReceiptReadabilityDialog]
 * shows the first such page and lets the user pick a better scan or continue anyway. It never
 * blocks; when the measurement itself fails the failure is logged and the upload continues as if
 * nothing was found.
 */
internal fun offerReadabilityHint(
    component: Component?,
    bytes: ByteArray,
    onProceed: () -> Unit,
    port: ReceiptReadabilityQueryPort,
) {
    val pages = try {
        port.findHardToReadPages(bytes)
    } catch (e: ReceiptReadabilityCheckException) {
        log.warn("Readability check failed, continuing the upload without a hint", e)
        emptyList()
    }
    if (pages.isEmpty()) {
        onProceed()
    } else {
        ReceiptReadabilityDialog(component, pages, onProceed).open()
    }
}

/** The "Beleg vermutlich schlecht lesbar" dialog: preview of the first reported page, rescan or accept. */
internal class ReceiptReadabilityDialog(
    private val component: Component?,
    private val pages: List<HardToReadPage>,
    private val onAccepted: () -> Unit,
) {
    private fun tr(key: String, vararg params: Any) = UiTranslations.tr(component, key, *params)

    private val dlg = AppDialog(tr("receipt.readability.title"), AppDialog.Size.S)

    fun open() {
        val preview = Image(previewHandler(pages.first().previewPng), "").apply {
            maxWidth = "100%"
            maxHeight = "50vh"
        }
        val body = Span(tr("receipt.readability.body", pages.joinToString(", ") { it.pageNumber.toString() }))
        val rescan = AppButtons.secondary(tr("receipt.readability.rescan")) { dlg.close() }
        val accept = AppButtons.primary(tr("receipt.readability.accept")) {
            dlg.close()
            onAccepted()
        }
        dlg.add(VerticalLayout(body, preview).apply { isPadding = false; isSpacing = true })
        dlg.footer(rescan, accept)
        dlg.open()
    }
}

private fun previewHandler(png: ByteArray): DownloadHandler =
    DownloadHandler.fromInputStream { DownloadResponse(ByteArrayInputStream(png), "preview.png", "image/png", png.size.toLong()) }
