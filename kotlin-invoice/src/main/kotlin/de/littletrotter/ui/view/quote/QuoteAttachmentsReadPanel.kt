package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.server.streams.DownloadEvent
import com.vaadin.flow.server.streams.DownloadResponse
import de.littletrotter.application.quote.port.inbound.QuoteAttachment
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentQueryPort
import de.littletrotter.shared.GermanTime
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asActionColumn
import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.asTextColumn
import de.littletrotter.ui.component.twoLineTextColumn
import de.littletrotter.ui.i18n.TranslationProvider
import java.text.NumberFormat
import java.util.Locale

private const val BYTES_PER_KIB = 1024.0

/** File size in kilobytes with German notation, the same wording in the editor and the detail view. */
internal fun attachmentSizeText(bytes: Long): String {
    val format = NumberFormat.getNumberInstance(Locale.GERMANY).apply { maximumFractionDigits = 1 }
    return "${format.format(bytes / BYTES_PER_KIB)} KB"
}

/** The bytes of [attachment] only when the download is requested; see [quoteFileResponse] for the failure answer. */
internal fun attachmentDownloadResponse(
    query: QuoteAttachmentQueryPort,
    quoteId: String,
    attachment: QuoteAttachment,
    event: DownloadEvent? = null,
): DownloadResponse = quoteFileResponse(event, attachment.fileName) { query.content(quoteId, attachment.id) }

/**
 * Where an offer file came from: the pool entry by its title and version number, or an upload; a
 * newer pool version is flagged. The title is what tells "AGB" from "Datenblatt" at a glance.
 */
internal fun attachmentOriginText(host: TranslationProvider, attachment: QuoteAttachment): String {
    val versionNo = attachment.versionNo ?: return host.tr("quote.attachments.origin.upload")
    val title = attachment.libraryTitle.orEmpty()
    val base = if (title.isBlank()) host.tr("quote.attachments.origin.pool", versionNo) else host.tr("quote.attachments.origin.poolTitled", title, versionNo)
    return if (attachment.newerVersionAvailable) "$base · ${host.tr("quote.attachments.newerVersion")}" else base
}

/**
 * The file name with its origin as second line, in the editor and the detail view alike. One
 * column instead of name, description and origin side by side: the origin stays readable on a
 * phone, where only the first column and the actions fit.
 */
internal fun Grid<QuoteAttachment>.attachmentFileColumn(host: TranslationProvider): Grid.Column<QuoteAttachment> =
    twoLineTextColumn({ it.fileName }, { attachmentOriginText(host, it) })
        .setHeader(host.tr("quote.attachments.name")).setKey("name").asTextColumn(TextColumnWidth.LARGE)

/**
 * The "Belege" tab of the offer detail: a read-only list of the attached files with name and
 * origin, kind, size, view and download; uploading and removing stay in the editor. An offer without attachments
 * shows one quiet sentence instead of an empty table.
 */
internal class QuoteAttachmentsReadPanel(
    private val host: TranslationProvider,
    private val query: QuoteAttachmentQueryPort,
) : VerticalLayout() {

    internal val grid = DataTable(QuoteAttachment::class.java)
    internal val emptyState = Span(host.tr("quote.attachments.empty"))

    private val labels = QuoteLabels(host)
    private var quoteId = ""

    init {
        isPadding = false
        setWidthFull()
        emptyState.addClassName("text-muted")
        grid.isAllRowsVisible = true
        grid.element.setAttribute(ARIA_LABEL, host.tr("quote.attachments.ariaLabel"))
        grid.attachmentFileColumn(host)
        grid.addColumn({ labels.attachmentKind(it.kind) }).setHeader(host.tr("quote.attachments.kind")).setKey("kind").asCompactColumn()
        grid.addColumn({ it.itemPosition?.toString() ?: "–" }).setHeader(host.tr("quote.attachment.column.item")).setKey("item").asCompactColumn()
        grid.addColumn({ attachmentSizeText(it.sizeBytes) }).setHeader(host.tr("quote.attachments.size")).setKey("size").asCompactColumn()
        grid.addColumn({ labels.date(GermanTime.dayOf(it.uploadedAt)) })
            .setHeader(host.tr("quote.attachments.uploadedAt")).setKey("uploadedAt").asCompactColumn()
        grid.addComponentColumn { download(it) }.setKey("actions").asActionColumn()
        add(emptyState, grid)
    }

    fun bind(id: String) {
        quoteId = id
        val items = guarded { query.attachments(id) } ?: emptyList()
        grid.setItems(items)
        emptyState.isVisible = items.isEmpty()
        grid.isVisible = items.isNotEmpty()
    }

    private fun download(attachment: QuoteAttachment): Component =
        quoteFileActions(host, attachment.fileName) { query.content(quoteId, attachment.id) }

    internal fun downloadResponse(attachment: QuoteAttachment): DownloadResponse =
        attachmentDownloadResponse(query, quoteId, attachment)
}
