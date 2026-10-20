package de.littletrotter.application.quote.port.inbound

import java.io.OutputStream

/** Exports the filtered offer list as a spreadsheet; reads from the offer component, never cached here. */
fun interface QuoteListWorkbookExportQueryPort {

    /**
     * Writes every offer [criteria] matches (its offset and limit are ignored, order and filter
     * apply) as a workbook with one sheet of offer headers and one sheet of all their items.
     * [translate] resolves a plain i18n key to display text. Does not close [output].
     */
    fun writeWorkbook(criteria: QuoteListCriteria, translate: (String) -> String, output: OutputStream)
}
