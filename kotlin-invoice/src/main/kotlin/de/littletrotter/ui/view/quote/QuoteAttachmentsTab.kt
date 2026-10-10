package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.radiobutton.RadioButtonGroup
import com.vaadin.flow.component.tabs.Tab
import com.vaadin.flow.component.tabs.TabSheet
import com.vaadin.flow.component.upload.Upload
import com.vaadin.flow.server.streams.DownloadResponse
import com.vaadin.flow.server.streams.UploadHandler
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
import de.littletrotter.application.quote.port.inbound.RemoveQuoteAttachmentUseCase
import de.littletrotter.application.quote.port.inbound.ReplaceLibraryVersionCommand
import de.littletrotter.shared.GermanTime
import de.littletrotter.ui.component.AppDialog
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.ActionBar
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.asActionColumn
import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.notification.UiNotifications

private const val MAX_UPLOAD_BYTES = 10 * 1024 * 1024
private const val NO_ITEM = "–"
private const val FALLBACK_MIME_TYPE = "application/octet-stream"

/** What the receipts tab needs from the offer component; bundled to keep the tab's constructor short, each member stays one narrow port. */
internal class QuoteAttachmentsTabPorts(
    val query: QuoteAttachmentQueryPort,
    val attach: AttachQuoteFileUseCase,
    val remove: RemoveQuoteAttachmentUseCase,
    val assign: AssignQuoteAttachmentUseCase,
    val library: AttachmentLibraryQueryPort,
    val attachLibrary: AttachLibraryDocumentUseCase,
)

/**
 * The "Belege" tab: the files of the offer, in three sub-tabs like the invoice's receipt dialog.
 * "Zugeordnet" lists what hangs on the offer, whether taken from the receipt pool or uploaded here;
 * "Belegpool" takes the current version of several pool documents over at once (customer documents
 * by definition); "Hochladen" adds a one-off file, internal or for the customer. Everything is
 * stored the moment it is chosen, so there is no separate save step. A file may optionally belong
 * to one line item, so the customer sees which item a drawing or data sheet documents.
 *
 * A pool document keeps the version it was taken in even when the pool gets a newer one; only here,
 * in the draft, does the row offer to swap to the newer version.
 */
internal class QuoteAttachmentsTab(
    private val host: TranslationProvider,
    private val ports: QuoteAttachmentsTabPorts,
) : VerticalLayout() {

    internal val kind = RadioButtonGroup<QuoteAttachmentKind>()
    internal val upload = Upload(UploadHandler.inMemory { metadata, bytes -> onUploaded(metadata.fileName(), metadata.contentType(), bytes) })
    internal val grid = DataTable(QuoteAttachment::class.java)
    internal val emptyState = Span(host.tr("quote.attachments.empty"))

    internal val itemBox = itemComboBox()
    internal val poolItemBox = itemComboBox()
    internal val pool = QuoteLibraryPickGrid(host, ports.library)
    internal val assignFromPool = AppButtons.primary(host.tr("quote.attachments.pool.assign")) { assignSelectedFromPool() }
    internal val assignedTab = Tab()
    internal val poolTab = Tab(host.tr("quote.attachments.tab.pool"))
    internal val uploadTab = Tab(host.tr("quote.attachments.tab.upload"))
    internal val subTabs = TabSheet()

    private val labels = QuoteLabels(host)
    private var offerItems: List<QuoteItem> = emptyList()
    private var quoteId = ""

    init {
        isPadding = true
        kind.label = host.tr("quote.attachments.kind")
        kind.setItems(QuoteAttachmentKind.entries)
        kind.setItemLabelGenerator { labels.attachmentKind(it) }
        kind.value = QuoteAttachmentKind.CUSTOMER
        upload.maxFileSize = MAX_UPLOAD_BYTES
        upload.isDropAllowed = true
        upload.setWidthFull()
        emptyState.addClassName("text-muted")
        configureColumns()
        subTabs.setWidthFull()
        subTabs.add(assignedTab, VerticalLayout(emptyState, grid).apply { isPadding = false })
        subTabs.add(poolTab, VerticalLayout(pool, ActionBar(poolItemBox, assignFromPool)).apply { isPadding = false })
        subTabs.add(
            uploadTab,
            VerticalLayout(
                Span(host.tr("quote.attachments.kind.hint")).apply { addClassName("text-muted") },
                ActionBar(kind, itemBox),
                upload,
            ).apply { isPadding = false },
        )
        add(subTabs)
        showAssignedCount(0)
    }

    fun bind(id: String, items: List<QuoteItem> = emptyList()) {
        quoteId = id
        offerItems = items.sortedBy { it.position }
        itemBox.setItems(offerItems)
        poolItemBox.setItems(offerItems)
        reload()
    }

    private fun configureColumns() {
        grid.isAllRowsVisible = true
        grid.element.setAttribute(ARIA_LABEL, host.tr("quote.attachments.ariaLabel"))
        grid.attachmentFileColumn(host)
        grid.addColumn({ labels.attachmentKind(it.kind) }).setHeader(host.tr("quote.attachments.kind")).setKey("kind").asCompactColumn()
        grid.addColumn({ it.itemPosition?.toString() ?: NO_ITEM }).setHeader(host.tr("quote.attachment.column.item")).setKey("item").asCompactColumn()
        grid.addColumn({ attachmentSizeText(it.sizeBytes) }).setHeader(host.tr("quote.attachments.size")).setKey("size").asCompactColumn()
        grid.addColumn({ labels.date(GermanTime.dayOf(it.uploadedAt)) })
            .setHeader(host.tr("quote.attachments.uploadedAt")).setKey("uploadedAt").asCompactColumn()
        grid.addComponentColumn { actions(it) }.setKey("actions").asActionColumn()
    }

    private fun itemComboBox(): ComboBox<QuoteItem> = ComboBox<QuoteItem>().apply {
        placeholder = host.tr("quote.attachment.item.none")
        isClearButtonVisible = true
        setItemLabelGenerator { "${it.position} · ${it.description}" }
    }

    private fun openAssignDialog(attachment: QuoteAttachment) {
        val dialog = AppDialog(host.tr("quote.attachment.assign.title", attachment.fileName), AppDialog.Size.S)
        val choice = itemComboBox().apply {
            label = host.tr("quote.attachment.assign.item")
            setItems(offerItems)
            value = offerItems.firstOrNull { it.position == attachment.itemPosition }
            setWidthFull()
        }
        val cancel = AppButtons.tertiary(host.tr("app.cancel")) { dialog.close() }
        val save = AppButtons.primary(host.tr("app.save")) {
            dialog.close()
            assign(attachment, choice.value)
        }
        dialog.add(choice)
        dialog.footer(cancel, save)
        dialog.open()
    }

    internal fun assign(attachment: QuoteAttachment, item: QuoteItem?) {
        if (guarded { ports.assign.assign(AssignQuoteAttachmentCommand(quoteId, attachment.id, item?.id)) } != null) reload()
    }

    private fun actions(attachment: QuoteAttachment): Component {
        val files = quoteFileActions(host, attachment.fileName) { ports.query.content(quoteId, attachment.id) }
        val assignAction = AppButtons.icon(ActionIcons.ATTACHMENTS, host.tr("quote.attachment.assign")) { openAssignDialog(attachment) }
        val delete = AppButtons.icon(ActionIcons.DELETE, host.tr("quote.attachments.delete")) { delete(attachment) }.apply {
            addThemeVariants(ButtonVariant.LUMO_ERROR)
        }
        val cell = HorizontalLayout(files, assignAction, delete)
        if (attachment.newerVersionAvailable && attachment.libraryVersionId != null) {
            cell.add(
                AppButtons.icon(ActionIcons.REFRESH, host.tr("quote.attachments.version.update")) { updateVersion(attachment) },
            )
        }
        return cell
    }

    internal fun downloadResponse(attachment: QuoteAttachment): DownloadResponse =
        attachmentDownloadResponse(ports.query, quoteId, attachment)

    internal fun onUploaded(fileName: String, mimeType: String?, bytes: ByteArray) {
        val file = QuoteAttachmentUpload(kind.value, fileName, mimeType ?: FALLBACK_MIME_TYPE, bytes, itemBox.value?.id)
        if (guarded { ports.attach.attach(quoteId, file) } != null) {
            UiNotifications.success(host.tr("quote.attachments.uploaded", fileName))
            reload()
        }
    }

    /** Takes the ticked pool documents over in their current version, then shows them under "Zugeordnet". */
    internal fun assignSelectedFromPool() {
        val chosen = pool.selected()
        if (chosen.isEmpty()) return
        val done = attachPoolEntries(ports.attachLibrary, quoteId, chosen, poolItemBox.value?.id)
        if (done > 0) {
            UiNotifications.success(host.tr("quote.attachments.pool.assigned", done))
            subTabs.selectedTab = assignedTab
        }
        reload()
    }

    /** Swaps [attachment] to the pool's current version of the entry it came from; the offer keeps the old one until then. */
    internal fun updateVersion(attachment: QuoteAttachment) {
        val entry = pool.allEntries.firstOrNull { candidate -> candidate.versions.any { it.id == attachment.libraryVersionId } } ?: return
        val command = ReplaceLibraryVersionCommand(quoteId, attachment.id, entry.currentVersion.id)
        if (guarded { ports.attachLibrary.replaceVersion(command) } != null) {
            UiNotifications.success(host.tr("quote.attachments.version.updated", entry.title, entry.currentVersion.versionNo))
            reload()
        }
    }

    private fun delete(attachment: QuoteAttachment) {
        if (guarded { ports.remove.remove(quoteId, attachment.id) } != null) reload()
    }

    private fun showAssignedCount(count: Int) {
        assignedTab.label = host.tr("quote.attachments.tab.assigned", count)
    }

    private fun reload() {
        val items = guarded { ports.query.attachments(quoteId) } ?: emptyList()
        grid.setItems(items)
        emptyState.isVisible = items.isEmpty()
        showAssignedCount(items.size)
        pool.reload()
        upload.clearFileList()
    }
}
