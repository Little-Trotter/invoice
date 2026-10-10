package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.radiobutton.RadioButtonGroup
import com.vaadin.flow.server.streams.DownloadHandler
import com.vaadin.flow.server.streams.DownloadResponse
import de.littletrotter.application.dto.InvoiceExportFilter
import de.littletrotter.application.export.InvoiceWorkbookExportPort
import de.littletrotter.shared.time.TimeSource
import org.jboss.logging.Logger
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import de.littletrotter.ui.component.ActionBar
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.AppDialog

// ── Mode B: the filtered-list export (invoice list toolbar) ────────────────────────
//
// PO decision (07.09.2026): three sheets only (Export, Rechnungen, Positionen — no Journal, a
// change journal belongs to one invoice, not a filtered set) and no business cap on the match
// count (the originally proposed 25 000-invoice ceiling was explicitly withdrawn). The button
// opens a dialog rather than downloading immediately, because the reader needs to see what the
// current filter matched before committing to it; the filter is snapshotted at the moment the
// button is clicked, so an edit made while the dialog is open never changes what Confirm builds.
//
// A later revision kept the format choice here — mode B's CSV covers more than one
// invoice's positions in one flat file, which the workbook cannot — but repainted it as a
// two-segment switch (theme `lt-segmented`, `styles.css`) rather than a labelled vertical radio
// group: the `RadioButtonGroup` keeps its keyboard/role=radiogroup semantics, only the paint job
// changes. The same package now also opens the dialog from an icon-only toolbar button, matching
// mode A's button (InvoiceExportDownloadGroup.kt).

private val log: Logger = Logger.getLogger("de.littletrotter.ui.view.invoice.InvoiceListExportDialog")

internal const val INVOICE_LIST_EXPORT_BUTTON_ID = "invoice-list-export-button"
internal const val INVOICE_LIST_EXPORT_FORMAT_CONTROL_ID = "invoice-list-export-format"
internal const val INVOICE_LIST_EXPORT_CONFIRM_ID = "invoice-list-export-confirm"
internal const val INVOICE_LIST_EXPORT_CANCEL_ID = "invoice-list-export-cancel"

/** The theme variant `styles.css` paints as a two-segment switch — see this file's own
 *  header comment for why the underlying control stays a plain [RadioButtonGroup]. */
private const val SEGMENTED_THEME_NAME = "lt-segmented"

/**
 * The two export formats mode B offers: the four-sheet workbook, or the positions-only CSV across
 * every matched invoice (R5). Mode A (the single-invoice export, `InvoiceExportDownloadGroup.kt`)
 * dropped its own copy of this choice later — a CSV of one invoice's positions duplicated the
 * workbook's own `Positionen` sheet — so this enum now belongs to mode B alone.
 */
internal enum class InvoiceExportDownloadFormat { WORKBOOK, CSV }

private const val WORKBOOK_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
private const val CSV_CONTENT_TYPE = "text/csv; charset=UTF-8"
private const val HTTP_INTERNAL_SERVER_ERROR = 500

private val FILE_DATE_PATTERN = DateTimeFormatter.ofPattern("yyyyMMdd")
private val FILE_TIMESTAMP_PATTERN = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

/**
 * Builds the toolbar button that opens the mode-B export dialog. [currentFilter] and
 * [currentScopeDescription] are read once, at click time, so the dialog always reflects the
 * filter as it stood the moment the button was pressed (see this file's own header comment).
 * [countMatches] powers the dialog's informational match count — never a gate, since there is no
 * cap.
 */
/** [service]/[tr]/[trWithArg] bundled so [buildInvoiceListExportButton]/
 *  [buildInvoiceListExportDialog]/[listDownloadHandlerFor] each take one parameter for them
 *  instead of two or three (LongParameterList) — the export mechanics, as opposed to
 *  those functions' own "what to export" parameters (filter/scopeDescription/matchCount/format). */
data class ExportServices(
    val service: InvoiceWorkbookExportPort,
    val tr: (String) -> String,
    val trWithArg: (String, Any) -> String,
    // The export file name carries the real Berlin wall-clock time of the moment the download
    // actually runs (inside a DownloadHandler callback, not at dialog-build time) — a plain UI
    // function may not read the clock itself (TimeSourceArchitectureTest).
    val time: TimeSource,
)

fun buildInvoiceListExportButton(
    currentFilter: () -> InvoiceExportFilter,
    currentScopeDescription: () -> String,
    countMatches: (InvoiceExportFilter) -> Long,
    services: ExportServices,
): Button = AppButtons.spreadsheetDownload {
    val filter = currentFilter()
    buildInvoiceListExportDialog(
        filter = filter,
        scopeDescription = currentScopeDescription(),
        matchCount = countMatches(filter),
        services = services,
    ).open()
}.apply {
    setId(INVOICE_LIST_EXPORT_BUTTON_ID)
}

/** Free-standing (not an `InvoiceListView` extension function) so it is directly unit-testable
 *  with a fake/mocked [ExportServices.service], the same idiom [buildInvoiceExportButton] (mode
 *  A) uses. */
internal fun buildInvoiceListExportDialog(
    filter: InvoiceExportFilter,
    scopeDescription: String,
    matchCount: Long,
    services: ExportServices,
): Dialog {
    val (_, tr, trWithArg) = services
    val dialog = AppDialog(tr("invoiceListExport.dialog.title"), AppDialog.Size.S)
    dialog.isCloseOnEsc = true
    dialog.isCloseOnOutsideClick = true

    val formatControl = RadioButtonGroup<InvoiceExportDownloadFormat>().apply {
        setId(INVOICE_LIST_EXPORT_FORMAT_CONTROL_ID)
        label = tr("invoiceExport.format.label")
        setItems(InvoiceExportDownloadFormat.WORKBOOK, InvoiceExportDownloadFormat.CSV)
        setItemLabelGenerator { format -> listExportFormatLabel(format, tr) }
        value = InvoiceExportDownloadFormat.WORKBOOK
        addThemeName(SEGMENTED_THEME_NAME)
        // The segments explain themselves (design spec) — the group's own "Format" label stays
        // for assistive technology (element.label, still announced) but is visually hidden by the
        // theme's own CSS (`vaadin-radio-group[theme~="lt-segmented"]::part(label)`), not by
        // setting it blank, which would remove it from the accessibility tree entirely.
        element.setAttribute("aria-label", tr("invoiceExport.format.label"))
    }

    lateinit var anchor: Anchor
    val confirmButton = AppButtons.primary(tr("invoiceListExport.dialog.confirm"), ActionIcons.DOWNLOAD) {
        dialog.close()
    }.apply { setId(INVOICE_LIST_EXPORT_CONFIRM_ID) }
    anchor = Anchor(listDownloadHandlerFor(formatControl.value, filter, scopeDescription, services), "").apply {
        element.setAttribute("download", true)
        add(confirmButton)
    }
    formatControl.addValueChangeListener { event ->
        val format = event.value ?: InvoiceExportDownloadFormat.WORKBOOK
        anchor.setHref(listDownloadHandlerFor(format, filter, scopeDescription, services))
        anchor.element.setAttribute("download", true)
    }

    val cancelButton = AppButtons.tertiary(tr("app.cancel")) { dialog.close() }
        .apply { setId(INVOICE_LIST_EXPORT_CANCEL_ID) }

    // Buttons live in the dialog's own body (not Dialog.footer): the footer slot attaches its
    // components as a *virtual* child element of the dialog rather than a normal one, which
    // Dialog.getChildren() never surfaces — untestable without deep reflection into Vaadin
    // internals. A plain trailing row inside the body is fully part of the component tree and
    // costs nothing visually the reader would notice.
    val actionsRow = ActionBar(cancelButton, anchor).apply {
        defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
    }

    dialog.add(
        VerticalLayout(
            Span(tr("invoiceListExport.dialog.scope.label")),
            Span(scopeDescription),
            Span(trWithArg("invoiceListExport.dialog.count", matchCount)),
            formatControl,
            actionsRow,
        ).apply { isPadding = false; isSpacing = true },
    )
    return dialog
}

private fun listExportFormatLabel(format: InvoiceExportDownloadFormat, tr: (String) -> String): String = when (format) {
    InvoiceExportDownloadFormat.WORKBOOK -> tr("invoiceExport.format.workbook")
    InvoiceExportDownloadFormat.CSV -> tr("invoiceExport.format.csv")
}

private fun listDownloadHandlerFor(
    format: InvoiceExportDownloadFormat,
    filter: InvoiceExportFilter,
    scopeDescription: String,
    services: ExportServices,
): DownloadHandler {
    return when (format) {
        InvoiceExportDownloadFormat.WORKBOOK ->
            DownloadHandler.fromInputStream { buildListWorkbookResponse(filter, scopeDescription, services) }
        InvoiceExportDownloadFormat.CSV ->
            DownloadHandler.fromInputStream { buildListCsvResponse(filter, services) }
    }
}

/**
 * Same "build into a temp file, stream it, delete on close" shape as mode A's
 * `buildWorkbookResponse` — see that function's own KDoc for why (a filtered set can be a
 * quarter of a million positions; holding that as an in-memory byte array is the incident that
 * warns against it). Same deliberate broad catch (AP-EXC-2): a
 * [com.vaadin.flow.server.streams.DownloadHandler] callback that lets an exception escape breaks
 * the whole download request, not just this one label. UI event boundary — sole catch-all of this
 * flow.
 */
private fun buildListWorkbookResponse(
    filter: InvoiceExportFilter,
    scopeDescription: String,
    services: ExportServices,
): DownloadResponse {
    val service = services.service
    val tr = services.tr
    val time = services.time
    val tempFile = File.createTempFile("invoice-list-export-", ".xlsx")
    return try {
        FileOutputStream(tempFile).use { out -> service.writeWorkbook(filter, scopeDescription, tr, out) }
        val fileName = invoiceListExportFileName(filter, base = "rechnungen", extension = "xlsx", localNow = time.localNow())
        DownloadResponse(TempFileCleanupInputStream(tempFile), fileName, WORKBOOK_CONTENT_TYPE, tempFile.length())
    } catch (e: Exception) {
        tempFile.deleteQuietly(log, "invoice list workbook export for filter=$filter")
        log.error("Failed to build invoice list workbook export for filter=$filter", e)
        DownloadResponse.error(HTTP_INTERNAL_SERVER_ERROR, tr("invoiceExport.error"))
    }
}

// UI event boundary — same DownloadHandler-callback reasoning as buildListWorkbookResponse above,
// sole catch-all of this flow.
private fun buildListCsvResponse(
    filter: InvoiceExportFilter,
    services: ExportServices,
): DownloadResponse {
    val service = services.service
    val tr = services.tr
    val time = services.time
    val tempFile = File.createTempFile("invoice-list-export-positions-", ".csv")
    return try {
        FileOutputStream(tempFile).use { out -> service.writePositionsCsv(filter, tr, out) }
        val fileName = invoiceListExportFileName(filter, base = "rechnungen-positionen", extension = "csv", localNow = time.localNow())
        DownloadResponse(TempFileCleanupInputStream(tempFile), fileName, CSV_CONTENT_TYPE, tempFile.length())
    } catch (e: Exception) {
        tempFile.deleteQuietly(log, "invoice list positions CSV export for filter=$filter")
        log.error("Failed to build invoice list positions CSV export for filter=$filter", e)
        DownloadResponse.error(HTTP_INTERNAL_SERVER_ERROR, tr("invoiceExport.error"))
    }
}

/**
 * `rechnungen_<von>_<bis>_<zeitstempel>.xlsx` / `rechnungen-positionen_<von>_<bis>_<zeitstempel>.csv`
 * per spec: `<von>`/`<bis>` are [filter]'s date bounds as `yyyyMMdd`, or `alle` when
 * that bound is unset; `<zeitstempel>` is `yyyyMMdd-HHmmss`. [localNow] is the business-zone wall
 * time ([de.littletrotter.shared.time.TimeSource.localNow]), read by the caller at the moment
 * the download actually runs (inside the [DownloadHandler] callback) — a plain UI function may
 * not read the clock itself (TimeSourceArchitectureTest).
 */
internal fun invoiceListExportFileName(
    filter: InvoiceExportFilter,
    base: String,
    extension: String,
    localNow: LocalDateTime,
): String {
    val von = filter.dateFrom?.format(FILE_DATE_PATTERN) ?: "alle"
    val bis = filter.dateTo?.format(FILE_DATE_PATTERN) ?: "alle"
    val timestamp = localNow.format(FILE_TIMESTAMP_PATTERN)
    return "${base}_${von}_${bis}_$timestamp.$extension".lowercase(Locale.ROOT)
}
