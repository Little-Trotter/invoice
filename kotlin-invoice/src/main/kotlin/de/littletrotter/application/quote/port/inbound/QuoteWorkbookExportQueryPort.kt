package de.littletrotter.application.quote.port.inbound

import java.io.OutputStream

/** Exports one offer as a spreadsheet; reads from the offer component, never cached here. */
fun interface QuoteWorkbookExportQueryPort {

    /**
     * Writes the offer [quoteId] as a workbook with one sheet of header data and one sheet of its
     * items. [translate] resolves a plain i18n key to display text. Does not close [output].
     * Throws `QuoteNotFoundException` when the offer does not exist (any more).
     */
    fun writeWorkbook(quoteId: String, translate: (String) -> String, output: OutputStream)
}
