package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import de.littletrotter.application.quote.port.inbound.AttachLibraryDocumentCommand
import de.littletrotter.application.quote.port.inbound.AttachLibraryDocumentUseCase
import de.littletrotter.application.quote.port.inbound.AttachmentLibraryEntry
import de.littletrotter.application.quote.port.inbound.AttachmentLibraryQueryPort
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.ellipsizedTextColumn
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asActionColumn
import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.asTextColumn
import de.littletrotter.ui.i18n.TranslationProvider

/**
 * The selectable entries of the offer receipt pool: every entry that is not archived, shown with
 * its current version, several of which can be ticked at once. Shared by the "Belegpool" sub-tab of
 * the offer's receipts and by the item dialog, so both pick from the pool the same way.
 */
internal class QuoteLibraryPickGrid(
    private val host: TranslationProvider,
    private val library: AttachmentLibraryQueryPort,
) : VerticalLayout() {

    internal val grid = DataTable(AttachmentLibraryEntry::class.java)
    internal val emptyState = Span(host.tr("quote.attachments.pool.empty"))

    private val labels = QuoteLabels(host)

    /** Every entry of the last read, archived ones included: an attachment may still carry a version of an archived entry. */
    var allEntries: List<AttachmentLibraryEntry> = emptyList()
        private set

    init {
        isPadding = false
        setWidthFull()
        emptyState.addClassName("text-muted")
        grid.selectionMode = Grid.SelectionMode.MULTI
        grid.isAllRowsVisible = true
        grid.element.setAttribute(ARIA_LABEL, host.tr("quote.attachments.pool.ariaLabel"))
        grid.ellipsizedTextColumn { it.title }.setHeader(host.tr("quote.receiptPool.column.title")).setKey("title").asTextColumn(TextColumnWidth.LARGE)
        grid.ellipsizedTextColumn { it.description }.setHeader(host.tr("quote.attachments.description")).setKey("description").asTextColumn(TextColumnWidth.MEDIUM)
        grid.addColumn({ host.tr("quote.attachments.version", it.currentVersion.versionNo) }).setHeader(host.tr("quote.receiptPool.column.version")).setKey("version").asCompactColumn()
        grid.addColumn({ labels.date(it.currentVersion.validFrom) }).setHeader(host.tr("quote.receiptPool.column.validFrom")).setKey("validFrom").asCompactColumn()
        grid.addColumn({ attachmentSizeText(it.currentVersion.sizeBytes) }).setHeader(host.tr("quote.attachments.size")).setKey("size").asCompactColumn()
        grid.addComponentColumn { entry ->
            quoteFileActions(host, entry.currentVersion.fileName) { library.versionContent(entry.id, entry.currentVersion.id) }
        }.setKey("actions").asActionColumn()
        add(emptyState, grid)
    }

    /** Reads the pool again and drops the selection. */
    fun reload() {
        allEntries = guarded { library.entries() } ?: emptyList()
        val selectable = allEntries.filterNot { it.archived }
        grid.setItems(selectable)
        grid.deselectAll()
        emptyState.isVisible = selectable.isEmpty()
        grid.isVisible = selectable.isNotEmpty()
    }

    fun selected(): List<AttachmentLibraryEntry> = grid.selectedItems.toList()
}

/**
 * Takes the current version of each of [entries] over into the offer, tied to [itemId] when given.
 * Each one is stored on its own, so a refusal for one entry (for example one already attached) does
 * not hold back the others; the number of entries that went through is returned.
 */
internal fun attachPoolEntries(
    attach: AttachLibraryDocumentUseCase,
    quoteId: String,
    entries: List<AttachmentLibraryEntry>,
    itemId: String?,
): Int = entries.count { entry ->
    guarded { attach.attach(AttachLibraryDocumentCommand(quoteId, entry.currentVersion.id, itemId)) } != null
}
