package de.littletrotter.ui.view.mail

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.data.renderer.ComponentRenderer
import com.vaadin.flow.router.RouteParameters
import com.vaadin.flow.router.RouterLink
import de.littletrotter.application.mail.port.inbound.MailJournalEntry
import de.littletrotter.application.mail.port.inbound.MailJournalSortColumn
import de.littletrotter.application.export.MailJournalCellText
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asActionColumn
import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.asTextColumn
import de.littletrotter.ui.format.DateFormatter
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.view.invoice.InvoiceDetailView
import java.time.ZoneOffset

/**
 * Column definitions, cell renderers and the column-key/sort-column mapping of [MailJournalView]'s
 * grid. Every sortable column sorts on the server (the page is only a window), so each carries a
 * no-op comparator: Vaadin's own in-memory sort would otherwise re-order the page the server just
 * ordered, and the two orders disagree on ties.
 */
internal class MailJournalGridColumns(
    private val translations: TranslationProvider,
    private val invoiceIdOf: (String) -> Long?,
    private val onRetryOfClick: (MailJournalEntry) -> Unit,
    private val actions: MailJournalRowActions,
    private val archiveDownload: MailJournalArchiveDownloadCell,
) {

    private val handover = MailJournalHandoverCell(translations)

    private fun tr(key: String, vararg params: Any): String = translations.tr(key, *params)

    fun configure(grid: DataTable<MailJournalEntry>) {
        grid.addClassName("mail-journal-table")
        grid.setSizeFull()
        grid.removeAllColumns()
        grid.sortable(KEY_CREATED_AT, "mail.journal.column.createdAt") { createdAtText(it) }.asCompactColumn()
        grid.sortable(KEY_STATUS, "mail.journal.column.status") { tr("mail.journal.status.${it.status.name}") }.asCompactColumn()
        referenceColumn(grid)
        grid.sortable(KEY_RECIPIENT, "mail.journal.column.recipient") { it.recipient }.asTextColumn(TextColumnWidth.MEDIUM)
        grid.sortable(KEY_SUBJECT, "mail.journal.column.subject") { it.subject }.asTextColumn(TextColumnWidth.LARGE)
        grid.sortable(KEY_TEMPLATE, "mail.journal.column.template") { MailJournalCellText.template(it) { key -> tr(key) } }
            .asTextColumn(TextColumnWidth.SMALL)
        grid.sortable(KEY_ARCHIVE, "mail.journal.column.archive") { tr("mail.journal.archive.${it.archiveStatus.name}") }.asCompactColumn()
        grid.addColumn(ComponentRenderer { entry: MailJournalEntry -> handover.build(entry) })
            .setKey(KEY_HANDOVER).asCompactColumn().setHeader(tr("mail.journal.column.handover"))
        retryOfColumn(grid)
        grid.addColumn(ComponentRenderer { entry: MailJournalEntry -> archiveDownload.build(entry) })
            .setKey(KEY_ARCHIVE_DOWNLOAD).asActionColumn().setHeader(tr("mail.journal.column.archiveDownload"))
        grid.addColumn(ComponentRenderer { entry: MailJournalEntry -> actions.build(entry) })
            .setKey(KEY_ACTIONS).asActionColumn().setHeader(tr("mail.journal.column.actions"))
        grid.element.setAttribute("aria-label", tr("mail.journal.table.ariaLabel"))
    }

    private fun DataTable<MailJournalEntry>.sortable(
        key: String,
        headerKey: String,
        text: (MailJournalEntry) -> String,
    ) = addColumn({ entry -> text(entry) })
        .setHeader(tr(headerKey))
        .setKey(key)
        .setSortable(true)
        .setSortProperty(key)
        .setComparator { _, _ -> 0 }

    private fun referenceColumn(grid: DataTable<MailJournalEntry>) {
        grid.addColumn(ComponentRenderer { entry: MailJournalEntry -> referenceCell(entry.referenceKey) })
            .setHeader(tr("mail.journal.column.reference"))
            .setKey(KEY_REFERENCE)
            .setSortable(true)
            .setSortProperty(KEY_REFERENCE)
            .setComparator { _, _ -> 0 }
            .asCompactColumn()
    }

    /** A link into the invoice when the reference resolves to one (quotes and deleted invoices do not), plain text otherwise. */
    private fun referenceCell(referenceKey: String): Component {
        val invoiceId = invoiceIdOf(referenceKey) ?: return Span(referenceKey)
        return RouterLink(referenceKey, InvoiceDetailView::class.java, RouteParameters("id", invoiceId.toString()))
    }

    private fun retryOfColumn(grid: DataTable<MailJournalEntry>) {
        grid.addColumn(
            ComponentRenderer { entry: MailJournalEntry ->
                entry.retryOfJournalId?.let { predecessor ->
                    // Narrows the list to the mail's reference: the chain of retries shares it.
                    AppButtons.tertiary("#$predecessor") { onRetryOfClick(entry) }.apply {
                        addThemeVariants(ButtonVariant.LUMO_SMALL)
                    }
                } ?: Span()
            },
        ).setHeader(tr("mail.journal.column.retryOf")).setKey(KEY_RETRY_OF).asCompactColumn()
    }

    private fun createdAtText(entry: MailJournalEntry): String =
        DateFormatter.format(entry.createdAt.atOffset(ZoneOffset.UTC))

    companion object {
        private const val KEY_CREATED_AT = "createdAt"
        private const val KEY_STATUS = "status"
        private const val KEY_REFERENCE = "referenceKey"
        private const val KEY_RECIPIENT = "recipient"
        private const val KEY_SUBJECT = "subject"
        private const val KEY_TEMPLATE = "template"
        private const val KEY_ARCHIVE = "archiveStatus"
        private const val KEY_HANDOVER = "archiveHandover"
        private const val KEY_RETRY_OF = "retryOf"
        private const val KEY_ARCHIVE_DOWNLOAD = "archiveDownload"
        private const val KEY_ACTIONS = "actions"

        private val SORT_COLUMNS = mapOf(
            KEY_CREATED_AT to MailJournalSortColumn.CREATED_AT,
            KEY_STATUS to MailJournalSortColumn.STATUS,
            KEY_REFERENCE to MailJournalSortColumn.REFERENCE_KEY,
            KEY_RECIPIENT to MailJournalSortColumn.RECIPIENT,
            KEY_SUBJECT to MailJournalSortColumn.SUBJECT,
            KEY_TEMPLATE to MailJournalSortColumn.TEMPLATE,
            KEY_ARCHIVE to MailJournalSortColumn.ARCHIVE_STATUS,
        )

        /** The server sort column a grid column key stands for; `null` for an unsortable column. */
        fun sortColumnForKey(key: String?): MailJournalSortColumn? = SORT_COLUMNS[key]

        fun keyForSortColumn(column: MailJournalSortColumn): String = SORT_COLUMNS.entries.first { it.value == column }.key
    }
}
