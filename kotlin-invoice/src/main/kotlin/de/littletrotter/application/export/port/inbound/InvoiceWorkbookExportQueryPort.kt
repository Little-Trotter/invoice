package de.littletrotter.application.export.port.inbound

import de.littletrotter.application.dto.InvoiceExportFilter
import java.io.OutputStream

/**
 * Input port for the spreadsheet and CSV exports of invoices. No spreadsheet-library type crosses
 * it; every label is resolved by the caller through [translate] because the export carries no
 * i18n bundle of its own. The caller owns [output] and closes it.
 */
interface InvoiceWorkbookExportQueryPort {
    /** Writes the four-sheet workbook of one invoice, with [appliedSelection] as its "which invoice" sentence. */
    fun writeWorkbook(invoiceId: Long, appliedSelection: String, translate: (String) -> String, output: OutputStream)

    /** Writes the filtered-list workbook for every invoice matching [filter]; an empty selection still yields a valid workbook. */
    fun writeWorkbook(filter: InvoiceExportFilter, appliedSelection: String, translate: (String) -> String, output: OutputStream)

    /** Writes the positions-only CSV across every invoice matching [filter]. */
    fun writePositionsCsv(filter: InvoiceExportFilter, translate: (String) -> String, output: OutputStream)
}
