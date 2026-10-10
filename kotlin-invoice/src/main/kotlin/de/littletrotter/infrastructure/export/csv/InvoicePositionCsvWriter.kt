package de.littletrotter.infrastructure.export.csv

import de.littletrotter.application.export.InvoicePositionExportRow
import de.littletrotter.infrastructure.export.workbook.InvoicePositionSheetLabels
import de.littletrotter.infrastructure.export.workbook.InvoicePositionSheetWriter
import de.littletrotter.infrastructure.export.workbook.TextGuard
import de.littletrotter.infrastructure.export.workbook.labelFor
import de.littletrotter.shared.Money
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Writes the CSV export: [InvoicePositionSheetWriter]'s `Positionen` sheet, flattened.
 * Same rows, same [InvoicePositionSheetWriter.DATA_COLUMN_COUNT] columns, same order —
 * minus the seven R4 control-formula columns, because CSV has none.
 *
 * Format: `;`-delimited, UTF-8 with a BOM (without it German Excel reads UTF-8
 * as Windows-1252 and mangles every umlaut), CRLF line endings, RFC 4180 quoting, decimal comma,
 * dates as `DD.MM.YYYY`. Every value this class writes goes through exactly the formatting or
 * sanitisation [InvoicePositionSheetWriter] already applies to the same field — see [CsvTextField]
 * for the text path — so the two formats can never disagree about what a cell shows.
 *
 * No control formulas: the columns that are formulas on the sheet are simply absent here, not
 * written as blank placeholders.
 *
 * Rows is filtered down to level-0 rows before anything is written: the bulk CSV (mode B)
 * is a flat list of positions, one per invoice line, and a trip's own detail rows would neither fit
 * that shape (they carry no tax/gross columns) nor stay meaningful without the XLSX sheet's control
 * block proving their sum — a reader who needs them uses mode A instead (the invoice's own XLSX).
 */
object InvoicePositionCsvWriter {

    private const val DELIMITER = ";"
    private const val LINE_ENDING = "\r\n"

    /** UTF-8 byte-order mark — see the class KDoc for why it is required, not optional. */
    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    private const val MONEY_SCALE = 2
    private const val QUANTITY_SCALE = 4
    private const val PERCENT_SCALE = 2

    private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    /**
     * Streams the header row followed by one row per [rows] to [output]. Does not close [output] —
     * that is the caller's responsibility, matching [de.littletrotter.infrastructure.export
     * .workbook.WorkbookWriter.write]'s own convention.
     *
     * @throws IllegalArgumentException if [labels] does not carry exactly
     *   [InvoicePositionSheetWriter.COLUMN_COUNT] column headers — the same guard
     *   [InvoicePositionSheetWriter.write] applies, since both consume the very same label set.
     */
    fun write(output: OutputStream, labels: InvoicePositionSheetLabels, rows: List<InvoicePositionExportRow>) {
        require(labels.columnHeaders.size == InvoicePositionSheetWriter.COLUMN_COUNT) {
            "InvoicePositionSheetLabels.columnHeaders must have ${InvoicePositionSheetWriter.COLUMN_COUNT} entries, " +
                "got ${labels.columnHeaders.size}"
        }
        output.write(BOM)
        val writer = BufferedWriter(OutputStreamWriter(output, StandardCharsets.UTF_8))
        writeLine(writer, dataColumnHeaders(labels))
        // Level-1 (trip detail) rows are excluded from the bulk CSV, see this class's own
        // KDoc.
        rows.filter { it.level == 0 }.forEach { row -> writeLine(writer, renderRow(row, labels)) }
        // Flushes into `output` without closing it — closing would close the caller's stream too.
        writer.flush()
    }

    private fun dataColumnHeaders(labels: InvoicePositionSheetLabels): List<String> =
        labels.columnHeaders.take(InvoicePositionSheetWriter.DATA_COLUMN_COUNT).map { renderHeader(it) }

    /** Header labels are trusted i18n text, not user input, so only [TextGuard.sanitize] (matching
     *  [de.littletrotter.infrastructure.export.workbook.SheetWriter.header]) and RFC 4180 quoting
     *  apply — no [CsvFormulaGuard], which exists solely for user-supplied position data. */
    private fun renderHeader(label: String): String = CsvFieldEscaper.escape(TextGuard.sanitize(label))

    private fun writeLine(writer: BufferedWriter, fields: List<String>) {
        writer.write(fields.joinToString(DELIMITER))
        writer.write(LINE_ENDING)
    }

    /** Exactly [InvoicePositionSheetWriter.write]'s per-row chain
     *  (`writeLinkAndOrder`/`writeDescription`/.../`writeContext`), stopping before its
     *  `writeControlBlock` — there is no CSV counterpart for that step. */
    private fun renderRow(row: InvoicePositionExportRow, labels: InvoicePositionSheetLabels): List<String> = listOf(
        integer(row.invoiceId),
        text(row.invoiceNumber.ifBlank { null }),
        text(row.positionId),
        integer(row.sortOrder.toLong()),
        text(labels.enumLabels.category.labelFor(row.category)),
        text(row.description),
        text(row.itemDescription),
        text(row.sellerItemIdentifier),
        text(row.buyerItemIdentifier),
        text(row.articleNumber),
        quantity(row.quantity),
        text(row.unit.symbol),
        text(row.unit.uneceCode),
        money(row.unitPriceNet),
        text(row.taxCategoryCode),
        text(InvoicePositionSheetWriter.taxCategoryLabel(row, labels)),
        percent(row.taxRatePercent),
        text(row.taxRuleCode),
        text(row.exemptionReasonCode),
        text(row.exemptionReasonText),
        text(row.taxLegalBasis),
        money(row.netAmount),
        money(row.vatAmount),
        money(row.grossAmount),
        date(row.servicePeriodFrom),
        date(row.servicePeriodTo),
        text(row.costCenter),
        text(row.deliveryNoteReference),
        integer(row.receiptsCount.toLong()),
        text(row.receiptFileNames),
        // [write] already filtered [rows] down to level-0 rows, so parentPositionId,
        // tripKind, travelDate and the whole per-diem block are always null here; only `level`
        // (always `0`) ever has a value. Written explicitly, not omitted, so this stays in
        // lock-step with InvoicePositionSheetWriter's own DATA_COLUMN_COUNT.
        integer(row.level.toLong()),
        text(row.parentPositionId),
        text(null),
        date(row.travelDate),
        text(null),
        text(null),
        text(null),
        text(null),
        "",
        "",
    )

    private fun text(value: String?): String = CsvTextField.render(value)

    /** [InvoicePositionExportRow]'s money fields (`unitPriceNet`, `netAmount`, `vatAmount`,
     *  `grossAmount`) are never nullable — unlike [percent] and [date], this has no blank-cell
     *  case to render. */
    private fun money(value: Money): String = decimal(value.amount, MONEY_SCALE)

    /** [InvoicePositionExportRow.quantity] is never nullable — see [money]'s own note. */
    private fun quantity(value: BigDecimal): String = decimal(value, QUANTITY_SCALE)

    /** [value] is the plain percentage number (`19` for 19 %) — matching [InvoicePositionSheetWriter
     *  .writeTax]'s own `RowWriter.percent`, never pre-divided by 100. Nullable: `null` means
     *  "missing", not "zero" (see [InvoicePositionExportRow]'s own KDoc on `taxRatePercent`). */
    private fun percent(value: BigDecimal?): String = value?.let { decimal(it, PERCENT_SCALE) } ?: ""

    /** [InvoicePositionExportRow]'s integer fields (`invoiceId`, `sortOrder`, `receiptsCount`) are
     *  never nullable — see [money]'s own note. */
    private fun integer(value: Long): String = value.toString()

    private fun date(value: LocalDate?): String = value?.format(DATE_FORMATTER) ?: ""

    /** Decimal comma, no thousands separator — a thousands separator would collide with the
     *  decimal comma on re-import (a known border-case). A negative amount keeps its
     *  single leading minus untouched: this path never goes through [CsvFormulaGuard]. */
    private fun decimal(value: BigDecimal, scale: Int): String =
        value.setScale(scale, RoundingMode.HALF_UP).toPlainString().replace('.', ',')
}
