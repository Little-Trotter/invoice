package de.littletrotter.infrastructure.export.workbook

import de.littletrotter.application.export.MailJournalCellText
import de.littletrotter.application.export.MailJournalWorkbookExportPort
import de.littletrotter.application.mail.port.inbound.MailJournalCriteria
import de.littletrotter.application.mail.port.inbound.MailJournalEntry
import de.littletrotter.application.mail.port.inbound.MailJournalQueryPort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.io.OutputStream
import java.time.ZoneOffset

private const val EXPORT_PAGE_SIZE = 200

private val COLUMN_KEYS = listOf(
    "mail.journal.column.createdAt",
    "mail.journal.column.status",
    "mail.journal.column.reference",
    "mail.journal.column.recipient",
    "mail.journal.column.subject",
    "mail.journal.column.template",
    "mail.journal.column.archive",
    "mail.journal.column.retryOf",
)

private val COLUMN_WIDTHS = listOf(
    ColumnWidths.DATE_TIME, ColumnWidths.CODE, ColumnWidths.CODE, ColumnWidths.NAME,
    ColumnWidths.TEXT, ColumnWidths.NAME, ColumnWidths.CODE, ColumnWidths.ID,
)

/**
 * Writes the journal workbook by walking the component's pages of [MailJournalCriteria] until one
 * comes back short. Reading page by page keeps memory flat for a journal of any length, and the
 * rows are streamed into the workbook as they arrive.
 */
@ApplicationScoped
class MailJournalWorkbookExportService @Inject constructor(
    private val journalQuery: MailJournalQueryPort,
) : MailJournalWorkbookExportPort {

    override fun writeWorkbook(criteria: MailJournalCriteria, translate: (String) -> String, output: OutputStream) {
        WorkbookWriter().use { writer ->
            val sheet = writer.sheet(translate("mail.journal.export.sheet"))
            sheet.header(COLUMN_KEYS.map(translate), COLUMN_WIDTHS)
            forEachRow(criteria) { entry -> sheet.row().writeRow(entry, translate) }
            writer.write(output)
        }
    }

    private fun forEachRow(criteria: MailJournalCriteria, consume: (MailJournalEntry) -> Unit) {
        var pageIndex = 0
        do {
            val page = journalQuery.findPage(criteria.copy(page = pageIndex, size = EXPORT_PAGE_SIZE))
            page.forEach(consume)
            pageIndex++
        } while (page.size == EXPORT_PAGE_SIZE)
    }

    private fun RowWriter.writeRow(entry: MailJournalEntry, translate: (String) -> String): RowWriter =
        dateTime(entry.createdAt.atOffset(ZoneOffset.UTC))
            .text(translate("mail.journal.status.${entry.status.name}"))
            .text(entry.referenceKey)
            .text(entry.recipient)
            .text(entry.subject)
            .text(MailJournalCellText.template(entry, translate))
            .text(translate("mail.journal.archive.${entry.archiveStatus.name}"))
            .integer(entry.retryOfJournalId)
}
