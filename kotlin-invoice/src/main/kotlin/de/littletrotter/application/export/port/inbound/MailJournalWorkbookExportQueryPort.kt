package de.littletrotter.application.export.port.inbound

import de.littletrotter.application.mail.port.inbound.MailJournalCriteria
import java.io.OutputStream

/**
 * Input port for exporting the mail journal as a workbook: one sheet with the list's columns and
 * every row the filter matches, across all pages. No spreadsheet-library type crosses it.
 */
fun interface MailJournalWorkbookExportQueryPort {
    /**
     * Writes the workbook for the rows [criteria] matches (its page window is ignored, its sort is
     * kept) to [output], which stays open for the caller. [translate] resolves a plain i18n key.
     */
    fun writeWorkbook(criteria: MailJournalCriteria, translate: (String) -> String, output: OutputStream)
}
