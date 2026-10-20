package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.upload.Upload
import com.vaadin.flow.server.streams.UploadHandler
import de.littletrotter.application.invoice.port.inbound.ReceiptReadabilityQueryPort
import de.littletrotter.application.quote.port.inbound.AssignQuoteAttachmentCommand
import de.littletrotter.application.quote.port.inbound.AssignQuoteAttachmentUseCase
import de.littletrotter.application.quote.port.inbound.AttachLibraryDocumentUseCase
import de.littletrotter.application.quote.port.inbound.AttachQuoteFileUseCase
import de.littletrotter.application.quote.port.inbound.AttachmentLibraryQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteAttachment
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentKind
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentUpload
import de.littletrotter.application.quote.port.inbound.QuoteItem
import de.littletrotter.ui.component.ActionBar
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.AppDialog
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asActionColumn
import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.asTextColumn
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.notification.UiNotifications

private const val MAX_UPLOAD_BYTES = 10 * 1024 * 1024
private const val FALLBACK_MIME_TYPE = "application/octet-stream"

/**
 * What the offer's item attachments need: the query for the list and, only in the editor, the write
 * use cases and the receipt pool. Without them the dialog is read-only (download only).
 */
internal class QuoteItemAttachmentPorts(
    val query: QuoteAttachmentQueryPort,
    val attach: AttachQuoteFileUseCase? = null,
    val assign: AssignQuoteAttachmentUseCase? = null,
    val library: AttachmentLibraryQueryPort? = null,
    val attachLibrary: AttachLibraryDocumentUseCase? = null,
    val readability: ReceiptReadabilityQueryPort? = null,
) {
    val readOnly: Boolean get() = attach == null || assign == null || readability == null

    /** Taking documents from the receipt pool needs both its query and the use case that attaches them. */
    val poolAvailable: Boolean get() = !readOnly && library != null && attachLibrary != null
}

/**
 * The attachments of one offer line behind its paperclip: the files of this line with download and
 * release (the file stays on the offer, only the link to the line is dropped), and below, right
 * aligned, the choice of an unassigned file of the offer plus an upload that lands on this line.
 * Every change is stored at once and reported through [onChanged] so the paperclip counts follow.
 */
internal class QuoteItemAttachmentsDialog(
    private val host: TranslationProvider,
    private val quoteId: String,
    private val item: QuoteItem,
    private val ports: QuoteItemAttachmentPorts,
    private val onChanged: () -> Unit,
) {

    internal val dialog = AppDialog(host.tr("quote.attachment.item.title", item.position), AppDialog.Size.L)
    internal val grid = DataTable(QuoteAttachment::class.java)
    internal val emptyState = Span(host.tr("quote.attachment.item.empty"))
    internal val assignBox = ComboBox<QuoteAttachment>(host.tr("quote.attachment.item.assign")).apply {
        setItemLabelGenerator { it.fileName }
        isClearButtonVisible = true
        addValueChangeListener { event -> event.value?.let { assign(it, item.id) } }
    }
    internal val upload = Upload(UploadHandler.inMemory { metadata, bytes -> onUploaded(metadata.fileName(), metadata.contentType(), bytes) }).apply {
        maxFileSize = MAX_UPLOAD_BYTES
    }
    internal val poolButton = AppButtons.secondary(host.tr("quote.attachment.item.pool")) { openPool() }
    internal val closeButton = AppButtons.tertiary(host.tr("app.close")) { dialog.close() }

    init {
        grid.isAllRowsVisible = true
        grid.element.setAttribute(ARIA_LABEL, host.tr("quote.attachments.ariaLabel"))
        grid.addColumn({ it.fileName }).setHeader(host.tr("quote.attachments.name")).setKey("name").asTextColumn(TextColumnWidth.LARGE)
        grid.addColumn({ attachmentSizeText(it.sizeBytes) }).setHeader(host.tr("quote.attachments.size")).setKey("size").asCompactColumn()
        grid.addComponentColumn { actions(it) }.setKey("actions").asActionColumn()
        emptyState.addClassName("text-muted")
        dialog.add(emptyState, grid)
        val footer: List<Component> = if (ports.readOnly) listOf(closeButton) else listOfNotNull(assignBox, upload, poolButton.takeIf { ports.poolAvailable }, closeButton)
        dialog.footer.add(ActionBar(*footer.toTypedArray()))
    }

    fun open() {
        reload()
        dialog.open()
    }

    private fun actions(attachment: QuoteAttachment): Component {
        val files = quoteFileActions(host, attachment.fileName) { ports.query.content(quoteId, attachment.id) }
        val cell = HorizontalLayout(files).apply { isPadding = false }
        if (!ports.readOnly) {
            cell.add(
                AppButtons.icon(ActionIcons.RELEASE, host.tr("quote.attachment.item.release")) { assign(attachment, null) },
            )
        }
        return cell
    }

    /** Links [attachment] to [itemId], or releases it from its line with `null`. */
    internal fun assign(attachment: QuoteAttachment, itemId: String?) {
        val assignPort = ports.assign ?: return
        if (guarded { assignPort.assign(AssignQuoteAttachmentCommand(quoteId, attachment.id, itemId)) } != null) changed()
    }

    internal fun onUploaded(fileName: String, mimeType: String?, bytes: ByteArray) {
        val attachPort = ports.attach ?: return
        val readability = ports.readability ?: return
        // A file attached to a line is meant for the customer to read along with that line.
        val type = mimeType ?: FALLBACK_MIME_TYPE
        decideQuoteUpload(host.getComponent(), readability, PickedFile(type, bytes)) { reduce ->
            store(attachPort, QuoteAttachmentUpload(QuoteAttachmentKind.CUSTOMER, fileName, type, bytes, item.id, reduce))
        }
    }

    private fun store(attachPort: AttachQuoteFileUseCase, file: QuoteAttachmentUpload) {
        val stored = guarded { attachPort.attach(quoteId, file) }
        if (stored == null) {
            upload.clearFileList()
            return
        }
        reload()
        showQuoteUploadResult(
            host.getComponent(),
            QuoteUploadResult(file.fileName, file.content.size, stored.reduction),
            host.tr("quote.attachments.uploaded", file.fileName),
        )
        onChanged()
    }

    /** Lets the user tick pool documents and ties each one's current version to this line. */
    private fun openPool() {
        val library = ports.library ?: return
        val attachLibrary = ports.attachLibrary ?: return
        val picker = QuoteLibraryPickGrid(host, library).apply { reload() }
        val pick = AppDialog(host.tr("quote.attachment.item.pool.title", item.position), AppDialog.Size.L)
        val cancel = AppButtons.tertiary(host.tr("app.cancel")) { pick.close() }
        val assignSelected = AppButtons.primary(host.tr("quote.attachments.pool.assign")) {
            val done = attachPoolEntries(attachLibrary, quoteId, picker.selected(), item.id)
            pick.close()
            if (done > 0) {
                UiNotifications.success(host.tr("quote.attachments.pool.assigned", done))
                changed()
            }
        }
        pick.add(picker)
        pick.footer(cancel, assignSelected)
        pick.open()
    }

    private fun changed() {
        reload()
        onChanged()
    }

    private fun reload(): List<QuoteAttachment> {
        val all = guarded { ports.query.attachments(quoteId) } ?: emptyList()
        val mine = all.filter { it.itemId == item.id }
        grid.setItems(mine)
        emptyState.isVisible = mine.isEmpty()
        assignBox.setItems(all.filter { it.itemId == null })
        assignBox.clear()
        upload.clearFileList()
        return all
    }
}
