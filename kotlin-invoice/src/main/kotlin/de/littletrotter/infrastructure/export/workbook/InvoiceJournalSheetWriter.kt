package de.littletrotter.infrastructure.export.workbook

import de.littletrotter.application.export.InvoiceJournalExportRow

/**
 * Writes the `Journal` sheet: one row per [InvoiceJournalExportRow], in `seq` ascending
 * order (the caller is expected to hand rows in that order already — this writer does not sort).
 *
 * This sheet exists only for a per-invoice export: an export over the invoice table carries no
 * journal sheet at all, since the journal belongs to a single invoice and across a filtered set it
 * would be neither readable nor meaningful. Nothing in this package offers a bulk, multi-invoice
 * read, so a caller assembling that other kind of export has no method here to call in the first
 * place — the restriction holds at the API boundary, not just by convention.
 *
 * Unlike [InvoiceHeaderSheetWriter]/[InvoicePositionSheetWriter] this sheet carries no R4
 * control-formula block: there is nothing here for Excel to recompute and check against, only a
 * change history to read.
 */
object InvoiceJournalSheetWriter {

    const val SHEET_NAME = "Journal"

    /** Total column count of the sheet. */
    const val COLUMN_COUNT = 13

    /** One width class per column, in the sheet's own fixed column order (link/order,
     *  timestamp, event, actor, detail, the three hash columns, then the "Vorgang"/
     *  "Vorgangs-Nr." pair, appended last — see InvoiceJournalColumnKeys's own KDoc on why
     *  appended rather than inserted). */
    internal val COLUMN_WIDTHS: List<Int> = listOf(
        ColumnWidths.ID, ColumnWidths.CODE, ColumnWidths.ID, ColumnWidths.DATE_TIME,
        ColumnWidths.NAME, ColumnWidths.NAME, ColumnWidths.CODE, ColumnWidths.TEXT,
        ColumnWidths.CODE, ColumnWidths.CODE, ColumnWidths.CODE,
        ColumnWidths.NAME, ColumnWidths.ID,
    )

    fun write(writer: WorkbookWriter, labels: InvoiceJournalSheetLabels, rows: List<InvoiceJournalExportRow>): SheetWriter {
        require(labels.columnHeaders.size == COLUMN_COUNT) {
            "InvoiceJournalSheetLabels.columnHeaders must have $COLUMN_COUNT entries, " +
                "got ${labels.columnHeaders.size}"
        }
        val sheet = writer.sheet(SHEET_NAME)
        sheet.header(labels.columnHeaders, COLUMN_WIDTHS)
        if (rows.isEmpty()) {
            sheet.noteRow().text(labels.noEntriesNoteLabel, wrap = true)
        } else {
            rows.forEach { row -> sheet.row().writeRow(row, labels) }
        }
        return sheet
    }

    private fun RowWriter.writeRow(row: InvoiceJournalExportRow, labels: InvoiceJournalSheetLabels): RowWriter =
        integer(row.invoiceId)
            .text(row.invoiceNumber?.ifBlank { null })
            .integer(row.seq)
            .dateTime(row.occurredAt)
            .text(labels.eventLabels.labelFor(row.event))
            .text(row.actorName ?: row.actor)
            .text(row.actor)
            .text(row.detail, wrap = true)
            .text(row.documentSha256)
            .text(row.entryHash)
            .text(row.prevHash)
            .text(labels.actionLabels.labelFor(row.action))
            .integer(row.businessTx)
}
