package de.littletrotter.application.export.port.outbound

import de.littletrotter.application.mail.port.inbound.MailJournalCriteria
import java.io.OutputStream

/**
 * Exports the mail journal as a workbook: one sheet with the list's columns and every row the
 * filter in [writeWorkbook] matches, across all pages. A pure application-layer port — no POI type
 * crosses it, so the UI depends on it without depending on the spreadsheet adapter.
 */
fun interface MailJournalWorkbookExportPort {

    /**
     * Writes the workbook for the rows [criteria] matches (its page window is ignored, its sort is
     * kept) to [output], which stays open for the caller. [translate] resolves a plain i18n key.
     * An empty selection still yields a valid workbook with its header row.
     */
    fun writeWorkbook(criteria: MailJournalCriteria, translate: (String) -> String, output: OutputStream)
}
