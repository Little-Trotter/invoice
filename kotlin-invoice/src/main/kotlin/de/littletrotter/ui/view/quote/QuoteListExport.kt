package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.server.streams.DownloadResponse
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.inbound.QuoteDetail
import de.littletrotter.application.quote.port.inbound.QuoteListCriteria
import de.littletrotter.application.quote.port.inbound.QuoteListWorkbookExportQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteWorkbookExportQueryPort
import de.littletrotter.ui.component.spreadsheetDownloadAnchor
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.view.invoice.TempFileCleanupInputStream
import de.littletrotter.ui.view.invoice.deleteQuietly
import org.jboss.logging.Logger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

private const val WORKBOOK_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
private const val WORKBOOK_SUFFIX = ".xlsx"
private const val QUOTE_LIST_WORKBOOK_BUTTON_ID = "quote-list-export-xlsx"
private const val QUOTE_WORKBOOK_BUTTON_ID = "quote-export-xlsx"
private const val HTTP_INTERNAL_SERVER_ERROR = 500

private val log: Logger = Logger.getLogger("de.littletrotter.ui.view.quote.QuoteListExport")

/** The toolbar download of the offer list as a workbook: offer headers and all their items, filtered like the list. */
internal fun buildQuoteListWorkbookLink(
    host: TranslationProvider,
    export: QuoteListWorkbookExportQueryPort,
    criteria: () -> QuoteListCriteria,
): Anchor = spreadsheetDownloadAnchor(QUOTE_LIST_WORKBOOK_BUTTON_ID) {
    quoteWorkbookResponse(host.tr("quoteExport.file.list")) { out -> export.writeWorkbook(criteria(), host::tr, out) }
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
private data class QuoteFileSpec(val baseName: String, val suffix: String, val contentType: String)

private fun quoteFileResponse(spec: QuoteFileSpec, write: (OutputStream) -> Unit): DownloadResponse {
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
