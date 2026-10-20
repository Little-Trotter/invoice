package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.server.streams.DownloadHandler
import com.vaadin.flow.server.streams.DownloadResponse
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.inbound.QuoteDetail
import de.littletrotter.application.quote.port.inbound.QuoteListCriteria
import de.littletrotter.application.quote.port.inbound.QuoteWorkbookExportQueryPort
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.ListExportDialogIds
import de.littletrotter.ui.component.ListExportDialogTexts
import de.littletrotter.ui.component.ListExportFormatBehavior
import de.littletrotter.ui.component.buildListExportDialog
import de.littletrotter.ui.component.spreadsheetDownloadAnchor
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.view.invoice.TempFileCleanupInputStream
import de.littletrotter.ui.view.invoice.deleteQuietly
import org.jboss.logging.Logger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.format.DateTimeFormatter

private const val WORKBOOK_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
private const val WORKBOOK_SUFFIX = ".xlsx"
private const val CSV_CONTENT_TYPE = "text/csv; charset=UTF-8"
private const val CSV_SUFFIX = ".csv"
internal const val QUOTE_LIST_EXPORT_BUTTON_ID = "quote-list-export-button"
internal const val QUOTE_LIST_EXPORT_FORMAT_ID = "quote-list-export-format"
internal const val QUOTE_LIST_EXPORT_CONFIRM_ID = "quote-list-export-confirm"
internal const val QUOTE_LIST_EXPORT_CANCEL_ID = "quote-list-export-cancel"
private val FILE_DATE_PATTERN = DateTimeFormatter.ofPattern("yyyyMMdd")
private const val QUOTE_WORKBOOK_BUTTON_ID = "quote-export-xlsx"
private const val HTTP_INTERNAL_SERVER_ERROR = 500

private val log: Logger = Logger.getLogger("de.littletrotter.ui.view.quote.QuoteListExport")

/** The two formats of the list export; the workbook is preselected like in the invoice list. */
internal enum class QuoteListExportFormat { WORKBOOK, CSV }

/**
 * The toolbar symbol of the offer list: the same button as in the invoice list, opening the shared export
 * dialog. The criteria are read once at the click, so an edit made while the dialog is open changes nothing.
 */
internal fun buildQuoteListExportButton(
    host: TranslationProvider,
    exports: QuoteListExportPorts,
    criteria: () -> QuoteListCriteria,
    countMatches: (QuoteListCriteria) -> Long,
): Button = AppButtons.spreadsheetDownload {
    val shown = criteria()
    buildQuoteListExportDialog(host, exports, shown, countMatches(shown)).open()
}.apply { setId(QUOTE_LIST_EXPORT_BUTTON_ID) }

internal fun buildQuoteListExportDialog(
    host: TranslationProvider,
    exports: QuoteListExportPorts,
    criteria: QuoteListCriteria,
    matchCount: Long,
): Dialog = buildListExportDialog(
    texts = ListExportDialogTexts(
        title = host.tr("quoteListExport.dialog.title"),
        scopeLabel = host.tr("quoteListExport.dialog.scope.label"),
        scope = quoteListExportScope(host, criteria),
        count = host.tr("quoteListExport.dialog.count", matchCount),
        formatLabel = host.tr("quoteListExport.format.label"),
        confirm = host.tr("quoteListExport.dialog.confirm"),
        cancel = host.tr("app.cancel"),
    ),
    ids = ListExportDialogIds(QUOTE_LIST_EXPORT_FORMAT_ID, QUOTE_LIST_EXPORT_CONFIRM_ID, QUOTE_LIST_EXPORT_CANCEL_ID),
    formats = listOf(
        QuoteListExportFormat.WORKBOOK to host.tr("quoteListExport.format.workbook"),
        QuoteListExportFormat.CSV to host.tr("quoteListExport.format.csv"),
    ),
    behavior = ListExportFormatBehavior(
        downloadFor = { format -> DownloadHandler.fromInputStream { quoteListDownload(host, exports, criteria, format) } },
        privacyHintFor = { _ -> host.tr("export.privacyHint.plain") },
    ),
)

/** What the export covers, in the words of the active filters. */
internal fun quoteListExportScope(host: TranslationProvider, criteria: QuoteListCriteria): String {
    val parts = listOfNotNull(
        criteria.status?.let { host.tr("quoteListExport.scope.status", host.tr("quote.status.${it.name}")) },
        criteria.text?.let { host.tr("quoteListExport.scope.text", it) },
    )
    return if (parts.isEmpty()) host.tr("quoteListExport.scope.all") else parts.joinToString(", ")
}

private fun quoteListDownload(
    host: TranslationProvider,
    exports: QuoteListExportPorts,
    criteria: QuoteListCriteria,
    format: QuoteListExportFormat,
): DownloadResponse {
    val baseName = host.tr("quoteExport.file.list") + "-" + exports.time.today().format(FILE_DATE_PATTERN)
    return when (format) {
        QuoteListExportFormat.WORKBOOK ->
            quoteWorkbookResponse(baseName) { out -> exports.workbook.writeWorkbook(criteria, host::tr, out) }
        QuoteListExportFormat.CSV ->
            quoteFileResponse(QuoteFileSpec(baseName, CSV_SUFFIX, CSV_CONTENT_TYPE)) { out ->
                exports.csv.writeCsv(criteria, host::tr, out)
            }
    }
}

/** The detail download of one offer as a workbook: its header data and its items. */
internal fun buildQuoteWorkbookLink(host: TranslationProvider, export: QuoteWorkbookExportQueryPort, detail: QuoteDetail): Anchor {
    val quoteId = detail.id
    val baseName = listOfNotNull(host.tr("quoteExport.file.single"), detail.number).joinToString("-")
    return spreadsheetDownloadAnchor(QUOTE_WORKBOOK_BUTTON_ID) {
        quoteWorkbookResponse(baseName) { out -> export.writeWorkbook(quoteId, host::tr, out) }
    }
}

internal fun quoteWorkbookResponse(baseName: String, write: (OutputStream) -> Unit): DownloadResponse =
    quoteFileResponse(QuoteFileSpec(baseName, WORKBOOK_SUFFIX, WORKBOOK_CONTENT_TYPE), write)

/** File name parts and media type of one export download. */
internal data class QuoteFileSpec(val baseName: String, val suffix: String, val contentType: String)

internal fun quoteFileResponse(spec: QuoteFileSpec, write: (OutputStream) -> Unit): DownloadResponse {
    val tempFile = File.createTempFile("quote-export-", spec.suffix)
    return try {
        FileOutputStream(tempFile).use(write)
        DownloadResponse(TempFileCleanupInputStream(tempFile), spec.baseName + spec.suffix, spec.contentType, tempFile.length())
    } catch (e: QuoteServiceException) {
        failed(tempFile, e)
    } catch (e: IOException) {
        failed(tempFile, e)
    }
}

private fun failed(tempFile: File, cause: Exception): DownloadResponse {
    tempFile.deleteQuietly(log, "quote export")
    log.error("Failed to build the offer export", cause)
    return DownloadResponse.error(HTTP_INTERNAL_SERVER_ERROR)
}
