package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.server.streams.DownloadHandler
import com.vaadin.flow.server.streams.DownloadResponse
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.export.port.inbound.InvoiceWorkbookExportQueryPort
import de.littletrotter.application.export.port.inbound.TripWorkbookExportQueryPort
import de.littletrotter.ui.component.blankToNull
import de.littletrotter.ui.component.spreadsheetDownloadAnchor
import org.jboss.logging.Logger
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.OutputStream
import java.util.Locale

// ── Per-invoice export button (invoice detail view) ─────────────────────────────────────────
//
// Mode A only (see the excel-export README's own scope table): the button exports exactly the
// invoice on screen, no scope dialog — the scope is already the invoice on screen. A PO decision
// (2026-09-10, "Bauhaus reading of Material") replaced the earlier format-control + text-
// button pair with a single icon-only button: one function, one form. The mode-A CSV alternative
// (the positions-only export of exactly one invoice) was deleted along with it, not hidden — the
// workbook's own `Positionen` sheet already carries the same rows, and mode B's bulk CSV (over a
// filtered list of invoices, InvoiceListExportDialog.kt) is the one CSV path that stayed, because
// it does something the workbook cannot: cover more than one invoice's positions in one flat file.
//
// This is the FIRST deliberate test id in this codebase (two setId calls
// existed before this, both incidental) — INVOICE_EXPORT_BUTTON_ID is a stable, translation-
// independent hook for the Playwright package; the next feature adding a test id should
// follow this same `setId` idiom rather than relying on aria-label/translated text alone.

private val log: Logger = Logger.getLogger("de.littletrotter.ui.view.invoice.InvoiceExportDownloadGroup")

internal const val INVOICE_EXPORT_BUTTON_ID = "invoice-export-button"

private const val WORKBOOK_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
private const val HTTP_INTERNAL_SERVER_ERROR = 500

/**
 * Builds the icon-only export button for [invoice], wrapped in the download [Anchor] it belongs
 * to (attachment, never inline). Free-standing (not an `InvoiceDetailView` extension function) so
 * it is directly unit-testable with a fake/mocked [service] and a plain `tr` lookup, the same
 * "extracted top-level function" idiom [de.littletrotter.ui.view.invoice.buildDownloadAction]
 * already uses.
 *
 * @param tr resolves a plain (no-argument) i18n key — also the button's `aria-label`/`title`.
 * @param trWithArg resolves a one-argument i18n key — used only for the `Export` sheet's "which
 *   invoice" selection sentence, the one label here that takes a parameter.
 */
fun buildInvoiceExportButton(
    invoice: InvoiceDetailDto,
    service: InvoiceWorkbookExportQueryPort,
    tr: (String) -> String,
    trWithArg: (String, Any) -> String,
): Component {
    val invoiceId = invoice.id
    val invoiceNumber = invoice.invoiceNumber

    return spreadsheetDownloadAnchor(INVOICE_EXPORT_BUTTON_ID) {
        buildWorkbookResponse(InvoiceRef(invoiceId, invoiceNumber), service, tr, trWithArg)
    }
}

/**
 * Streams the workbook into a temporary file (never held in memory — a whole invoice's worth of
 * positions/journal entries as an in-memory byte array is exactly the kind of incident this
 * design avoids) and hands back a stream that deletes that file the moment it is closed (see
 * [TempFileCleanupInputStream]). Any failure before the response is built deletes the half-written
 * file itself, in a `finally`, and turns into [DownloadResponse.error] rather than an uncaught
 * exception escaping the download request.
 *
 * Same broad-catch idiom as [InvoiceMailActions]'s own KDoc describes — the
 * broad catch below is deliberate: a [com.vaadin.flow.server.streams.DownloadHandler] callback
 * that lets an exception escape breaks the whole download request, not just this one label.
 * Suppressed at the source rather than parked in detekt-baseline.xml, which this repository
 * ratchets downwards only.
 */
/** An invoice's id and printed number — bundled so [buildWorkbookResponse] takes one parameter
 *  for them instead of two, under detekt's `LongParameterList` threshold. */
private data class InvoiceRef(val id: Long, val number: String)

private fun buildWorkbookResponse(
    invoiceRef: InvoiceRef,
    service: InvoiceWorkbookExportQueryPort,
    tr: (String) -> String,
    trWithArg: (String, Any) -> String,
): DownloadResponse {
    val (invoiceId, invoiceNumber) = invoiceRef
    val selectionSubject = invoiceNumber.blankToNull() ?: invoiceId.toString()
    val appliedSelection = trWithArg("invoiceExport.export.selection.singleInvoice", selectionSubject)
    return workbookDownloadResponse(
        DownloadSpec(
            tempPrefix = "invoice-export-",
            fileName = invoiceExportFileName(invoiceNumber, invoiceId, suffix = "", extension = "xlsx"),
            logContext = "invoice workbook export for invoiceId=$invoiceId",
        ),
        tr,
    ) { out -> service.writeWorkbook(invoiceId, appliedSelection, tr, out) }
}

/**
 * The trip dialog's download handler: one workbook for the `TRIP` position [positionId] of
 * [invoice]; same anchor/handler idiom as [buildInvoiceExportButton], the button itself lives in
 * the dialog footer (it carries the dialog's own test id).
 */
internal fun tripWorkbookDownloadHandler(
    invoice: InvoiceDetailDto,
    positionId: String,
    service: TripWorkbookExportQueryPort,
    tr: (String) -> String,
): DownloadHandler = DownloadHandler.fromInputStream {
    workbookDownloadResponse(
        DownloadSpec(
            tempPrefix = "trip-export-",
            fileName = service.fileName(invoice.invoiceNumber, invoice.id, positionId),
            logContext = "trip workbook export for invoiceId=${invoice.id} positionId=$positionId",
        ),
        tr,
    ) { out -> service.writeTripWorkbook(invoice.id, positionId, tr, out) }
}

/** Renders a workbook into a temp file that is deleted once the response stream is closed; any
 *  failure becomes a [DownloadResponse.error] instead of escaping the request. UI event boundary —
 *  a DownloadHandler callback must not let ANY exception escape the request, sole catch-all of
 *  this flow. */
/** [tempPrefix]/[fileName]/[logContext] bundled so [workbookDownloadResponse] takes one
 *  parameter for them instead of three, under detekt's `LongParameterList` threshold. */
private data class DownloadSpec(
    val tempPrefix: String,
    val fileName: String,
    val logContext: String,
)

private fun workbookDownloadResponse(
    spec: DownloadSpec,
    tr: (String) -> String,
    write: (OutputStream) -> Unit,
): DownloadResponse {
    val tempFile = File.createTempFile(spec.tempPrefix, ".xlsx")
    return try {
        FileOutputStream(tempFile).use(write)
        DownloadResponse(TempFileCleanupInputStream(tempFile), spec.fileName, WORKBOOK_CONTENT_TYPE, tempFile.length())
    } catch (e: Exception) {
        tempFile.deleteQuietly(log, "cleanup after ${spec.logContext}")
        log.error("Failed to build ${spec.logContext}", e)
        DownloadResponse.error(HTTP_INTERNAL_SERVER_ERROR, tr("invoiceExport.error"))
    }
}

internal fun invoiceExportFileName(invoiceNumber: String, invoiceId: Long, suffix: String, extension: String): String {
    val sanitized = invoiceNumber.replace("/", "-").blankToNull()
    val base = sanitized ?: "id-$invoiceId"
    return "rechnung_$base$suffix.$extension".lowercase(Locale.ROOT)
}

/**
 * Wraps a [FileInputStream] over [file] so that closing the stream — which is exactly what
 * [com.vaadin.flow.server.streams.InputStreamDownloadHandler] does once the response body has been
 * written — deletes [file] in a `finally`, matching the "delete the temp file when the stream
 * closes" rule. A failed [FileInputStream.close] must not leave the temp file behind, hence the
 * `finally` rather than a plain sequential `close(); file.delete()`.
 */
internal class TempFileCleanupInputStream(private val file: File) : FilterInputStream(FileInputStream(file)) {
    override fun close() {
        try {
            super.close()
        } finally {
            file.deleteQuietly(log, "download stream closed")
        }
    }
}

/** Best-effort delete of a download temp file (kotlin:S899 — the boolean result of [File.delete]
 *  must not be silently ignored): the OS temp dir reclaims it eventually regardless, so a failed
 *  delete is a warning, not an error — but a warning that would otherwise vanish entirely. */
internal fun File.deleteQuietly(log: Logger, context: String) {
    if (!delete()) {
        log.warn("Could not delete temp file $absolutePath ($context)")
    }
}
