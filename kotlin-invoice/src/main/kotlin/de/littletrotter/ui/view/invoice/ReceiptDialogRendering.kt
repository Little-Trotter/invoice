package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.server.streams.DownloadEvent
import com.vaadin.flow.server.streams.DownloadResponse
import de.littletrotter.application.dto.ReceiptRowDto
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.asNumericColumn
import de.littletrotter.ui.component.asTextColumn
import de.littletrotter.ui.component.ellipsizedTextColumn
import de.littletrotter.ui.notification.UiNotifications
import java.io.ByteArrayInputStream
import de.littletrotter.ui.component.emptyFileResponse
import de.littletrotter.ui.component.fileDownloadAnchor
import de.littletrotter.ui.component.fileViewAnchor

private const val TITLE_ATTR = "title"

/**
 * [ReceiptDialog]'s shared grid rendering: the download/view anchors both the attached
 * and pool grids use, and the two column blocks ([DataTable.addFileIdentityColumns] for both
 * grids, [DataTable.addAssignedToColumn] for the Invoice target's attached grid only). Extension
 * functions on [ReceiptDialog], not a composer class: unlike [ReceiptPoolPane] this holds no state
 * of its own, only reusable rendering — the same "own file, extracted so `ReceiptDialog.kt` does
 * not grow further" reasoning as `ReceiptDialogAttachedTab.kt`.
 */
internal fun ReceiptDialog.downloadAnchor(receipt: ReceiptRowDto): Anchor =
    fileDownloadAnchor(receipt.fileName, tr("receipt.action.download")) { event -> receiptDownloadResponse(event, receipt) }

internal fun ReceiptDialog.viewAnchor(receipt: ReceiptRowDto): Anchor =
    fileViewAnchor(tr("receipt.action.view")) { event -> receiptDownloadResponse(event, receipt) }

/**
 * A [DownloadHandler] callback that lets an exception escape breaks the whole
 * download request (stack trace in the log, a broken response in the browser); previously
 * neither anchor above caught anything, so a receipt whose object was missing from RustFS (or
 * an S3 call that timed out) surfaced exactly that way. [de.littletrotter.application.invoice
 * .ReceiptPoolService.getBytes] now throws [de.littletrotter.domain.exception.ReceiptNotFoundException]
 * instead of letting the S3 client's own exception escape; caught broadly here anyway (same idiom
 * as the pool `DataProvider`'s fetch/count closures) since a `DownloadHandler` callback must never
 * let ANY exception through, not just the one type the service currently documents.
 *
 * The failure is surfaced as a notification, not a stack trace: logged once at WARN, then
 * shown via [event]'s own [DownloadEvent.getUI] — a download request runs on its own HTTP
 * thread, not the UI's regular event-processing thread (see that method's own KDoc on why
 * [com.vaadin.flow.component.UI.access] is required here, the same idiom
 * [de.littletrotter.ui.UiEventBus] uses for its own background-thread-to-UI dispatch).
 *
 * Reads via [de.littletrotter.application.invoice.port.inbound.InvoiceReceiptQueryPort
 * .getBytes] now, same underlying `ReceiptPoolService.getBytes` call, no behaviour change.
 *
 * The response itself is answered as empty content, not [DownloadResponse.error]: unlike the
 * export dialogs' own download handlers (which trigger a same-tab file save),
 * [viewAnchor] opens inline in a brand new tab (`target=_blank`) — an HTTP error status there
 * would render as a raw, ugly browser error page in that new tab instead of just staying
 * blank, while the notification (in the ORIGINAL tab, where this dialog lives) already told
 * the user what happened.
 */
// UI event boundary — a DownloadHandler callback must not let ANY exception through, sole catch-all
// of this flow — see this function's own KDoc.
private fun ReceiptDialog.receiptDownloadResponse(event: DownloadEvent, receipt: ReceiptRowDto): DownloadResponse =
    try {
        val data = invoiceReceiptQueryPort.getBytes(receipt.id)
        DownloadResponse(ByteArrayInputStream(data), receipt.fileName, "application/pdf", data.size.toLong())
    } catch (ex: Exception) {
        log.warn("Failed to download receipt id=${receipt.id} (${gateway.targetContext()}): ${ex.message}")
        event.ui?.access { UiNotifications.error(tr("receipt.error.load", ex.message ?: "")) }
        emptyFileResponse(receipt.fileName, "application/pdf")
    }

/**
 * File name and description can be long; ellipsized with the full text as a
 * mouse-over tooltip. Shared by the attached-grid and pool-grid: both list the same [ReceiptRowDto]
 * identity, so the ellipsis/tooltip treatment can't drift between "already attached" and "pick
 * from pool" the way two independently maintained copies could.
 */
internal fun ReceiptDialog.addFileIdentityColumns(table: DataTable<ReceiptRowDto>) {
    table.ellipsizedTextColumn { it.fileName }.setHeader(tr("receipt.column.fileName")).asTextColumn(TextColumnWidth.MEDIUM)
    table.ellipsizedTextColumn { it.description }.setHeader(tr("receipt.column.description")).asTextColumn(TextColumnWidth.MEDIUM)
    table.addColumn { formatReceiptDocumentDate(it.documentDate) }.setHeader(tr("receipt.column.documentDate")).asCompactColumn()
    table.addColumn { formatReceiptSize(it.sizeBytes) }.setHeader(tr("receipt.column.size")).asNumericColumn()
}

/**
 * fix — leading "Zugeordnet an" column, [ReceiptTarget.Invoice]'s attached grid only: names
 * where each row's link lives, read straight off [AttachedReceiptRow] (no side lookup — the row
 * carries its own location/read-only facts). A read-only (position) row also carries the "edit on
 * the position's own dialog" hint as its native-tooltip `title` attribute — that row has no detach
 * button, so the tooltip is the only place saying where editing actually happens.
 */
internal fun ReceiptDialog.addAssignedToColumn(table: DataTable<AttachedReceiptRow>) {
    table.addComponentColumn { row ->
        Span(row.locationLabel).apply {
            if (row.readOnly) {
                element.setAttribute(TITLE_ATTR, tr("invoiceDetail.receipts.readOnlyHint"))
            }
        }
    }.setHeader(tr("invoiceDetail.receipts.column.assignedTo")).asCompactColumn()
}
