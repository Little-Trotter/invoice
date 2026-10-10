package de.littletrotter.ui.view.mail

import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.server.streams.DownloadHandler
import com.vaadin.flow.server.streams.DownloadResponse
import de.littletrotter.application.export.MailJournalWorkbookExportPort
import de.littletrotter.application.mail.MailDispatchException
import de.littletrotter.application.mail.port.inbound.MailJournalCriteria
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.ui.component.spreadsheetDownloadAnchor
import de.littletrotter.ui.view.invoice.TempFileCleanupInputStream
import de.littletrotter.ui.view.invoice.deleteQuietly
import org.jboss.logging.Logger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val log: Logger = Logger.getLogger("de.littletrotter.ui.view.mail.MailJournalExport")

private const val WORKBOOK_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
private const val HTTP_INTERNAL_SERVER_ERROR = 500
private val FILE_DATE_PATTERN = DateTimeFormatter.ofPattern("yyyyMMdd")
private val FILE_TIMESTAMP_PATTERN = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

internal const val MAIL_JOURNAL_EXPORT_BUTTON_ID = "mail-journal-export-button"

/** What the export needs besides the filter: the workbook writer, text resolution and the clock. */
internal class MailJournalExportServices(
    val service: MailJournalWorkbookExportPort,
    val tr: (String) -> String,
    val time: TimeSource,
)

/**
 * Excel download icon inside a download link. [currentCriteria] is read when the download
 * actually runs, so the file always holds the filter set on screen at that moment, all pages.
 */
internal fun buildMailJournalExportButton(
    currentCriteria: () -> MailJournalCriteria,
    services: MailJournalExportServices,
): Anchor {
    return spreadsheetDownloadAnchor(MAIL_JOURNAL_EXPORT_BUTTON_ID) { buildExportResponse(currentCriteria(), services) }
}

/**
 * Builds the workbook into a temp file and streams it, deleting the file on close: a journal can
 * be long and must not be held as one byte array. A [DownloadHandler] callback that lets an
 * exception escape breaks the whole download request, so the two failure families this flow has
 * (component unreachable, temp file unwritable) become a plain HTTP error with a readable text.
 */
internal fun buildExportResponse(criteria: MailJournalCriteria, services: MailJournalExportServices): DownloadResponse {
    val tempFile = File.createTempFile("mail-journal-export-", ".xlsx")
    return try {
        FileOutputStream(tempFile).use { out -> services.service.writeWorkbook(criteria, services.tr, out) }
        val fileName = mailJournalExportFileName(criteria, services.time.localNow())
        DownloadResponse(TempFileCleanupInputStream(tempFile), fileName, WORKBOOK_CONTENT_TYPE, tempFile.length())
    } catch (e: IOException) {
        failed(tempFile, e, services)
    } catch (e: MailDispatchException) {
        failed(tempFile, e, services)
    }
}

private fun failed(tempFile: File, cause: Exception, services: MailJournalExportServices): DownloadResponse {
    tempFile.deleteQuietly(log, "mail journal export")
    log.error("Failed to build the mail journal export", cause)
    return DownloadResponse.error(HTTP_INTERNAL_SERVER_ERROR, services.tr("mail.journal.export.error"))
}

/** `mail-journal_<von>_<bis>_<zeitstempel>.xlsx`; an unset date bound reads `alle`. */
internal fun mailJournalExportFileName(criteria: MailJournalCriteria, localNow: LocalDateTime): String {
    val von = criteria.from?.format(FILE_DATE_PATTERN) ?: "alle"
    val bis = criteria.to?.format(FILE_DATE_PATTERN) ?: "alle"
    return "mail-journal_${von}_${bis}_${localNow.format(FILE_TIMESTAMP_PATTERN)}.xlsx"
}
