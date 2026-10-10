package de.littletrotter.infrastructure.export.workbook.trip

import de.littletrotter.application.export.TripWorkbookView
import de.littletrotter.infrastructure.export.workbook.ColumnWidths
import de.littletrotter.infrastructure.export.workbook.ExcelColumnLetters
import de.littletrotter.infrastructure.export.workbook.RowWriter
import de.littletrotter.infrastructure.export.workbook.SheetWriter
import de.littletrotter.infrastructure.export.workbook.WorkbookWriter
import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import de.littletrotter.shared.TripExpenseExportDto

/**
 * Writes the "Posten" sheet: one row per [de.littletrotter.shared.TripExpenseExportDto]
 * (net computed as a real Excel formula, quantity × unit price — the whole point of this workbook
 * is letting a reader recompute the price themselves), then a total row summing the shared "Netto" column
 * and cross-checking it against [TripDetailSheetWriter]'s own "Berechnete Summe (Netto)" cell (R4
 * — a Δ ≠ 0 here would mean the two independently-derived figures disagree, a genuine finding).
 * Net only — no VAT column anywhere on this sheet (PO decision, rk-5-trip-excel.md).
 *
 * Border case (README 7/1): an expense-only trip and a fixed-price-only trip
 * (no expenses) are both valid — the last one writes only the total row (see
 * [writeTotalRow]'s own KDoc for how its "Netto" cell reads `0` in that case), matching
 * [de.littletrotter.shared.TripExportDto.calculatedNet] for that case.
 *
 * Deliberately narrower than the export DTO's own aspirational shape (rk-2-application.md item 5):
 * [de.littletrotter.shared.TripExpenseExportDto] carries no receipt file name,
 * so neither column exists here — this writer only ever reads the fields the DTOs actually carry (the brief:
 * read the actual classes), not the ones an earlier planning doc described. The item-level receipt
 * count on [TripDetailSheetWriter]'s own sheet still covers that side of the story.
 */
object TripExpenseSheetWriter {

    const val SHEET_NAME = "Posten"
    const val COLUMN_COUNT = 14

    /** Excel is 1-based and row 1 is the header, so the first data row is row 2 — mirrors
     *  [de.littletrotter.infrastructure.export.workbook.InvoicePositionSheetWriter]'s own
     *  convention. */
    private const val FIRST_DATA_ROW = 2

    /** Column indices the formula/total-row block (only) needs to address by letter — every
     *  other column is written strictly in sequence via the chained [RowWriter] calls below,
     *  same convention as [de.littletrotter.infrastructure.export.workbook.InvoiceHeaderSheetWriter]'s
     *  own `Col`. */
    private object Col {
        const val QUANTITY = 4
        const val UNIT_PRICE_NET = 6
        const val NET_AMOUNT = 7
    }

    /** `internal`, not `private`: de.littletrotter.infrastructure.export.workbook.SheetWriterTest's
     *  own "no width below the FLAG floor" guard reads this directly. */
    internal val COLUMN_WIDTHS: List<Int> = listOf(
        ColumnWidths.ID, ColumnWidths.NAME, ColumnWidths.TEXT, ColumnWidths.DATE,
        ColumnWidths.QUANTITY, ColumnWidths.NAME, ColumnWidths.MONEY, ColumnWidths.MONEY,
        ColumnWidths.FLAG, ColumnWidths.FLAG, ColumnWidths.FLAG, ColumnWidths.FLAG,
        ColumnWidths.MONEY, ColumnWidths.MONEY,
    )

    /** [positionId]/[currency]/[labels] stay constant for the whole [write] call, unlike
     *  [RowCursor]'s own per-row [RowCursor.lineNumber]/[RowCursor.excelRow] — bundled so
     *  [writeExpenseRow] takes one parameter for it instead of three (LongParameterList). */
    private data class SheetContext(
        val positionId: String,
        val currency: Currency,
        val labels: TripExpenseSheetLabels,
    )

    /** One expense row's own place in the sheet: its item sub-number ([lineNumber], "3.2") and its
     *  physical Excel row ([excelRow]) — the two counters [write] increments together per row. */
    private data class RowCursor(val lineNumber: Int, val excelRow: Int)

    /** The sheet's data-row extent (Excel row numbers, inclusive) [writeTotalRow]'s `SUM` formula
     *  ranges over — bundled so that function takes one parameter for it instead of two
     *  (LongParameterList). */
    private data class DataRowRange(val first: Int, val last: Int)

    fun write(writer: WorkbookWriter, labels: TripExpenseSheetLabels, view: TripWorkbookView): SheetWriter {
        require(labels.columnHeaders.size == COLUMN_COUNT) {
            "TripExpenseSheetLabels.columnHeaders must have $COLUMN_COUNT entries, got ${labels.columnHeaders.size}"
        }
        val sheet = writer.sheet(SHEET_NAME)
        sheet.header(labels.columnHeaders, COLUMN_WIDTHS)

        val ctx = SheetContext(view.positionId, view.currency, labels)
        var lineNumber = 0
        var excelRow = FIRST_DATA_ROW
        view.expenses.forEach { expense ->
            lineNumber++
            sheet.row().writeExpenseRow(RowCursor(lineNumber, excelRow), expense, ctx)
            excelRow++
        }
        // Per-diem days have their own Verpflegung sheet; Posten contains general trip expenses.
        val lastDataRow = excelRow - 1
        writeTotalRow(sheet, labels, DataRowRange(FIRST_DATA_ROW, lastDataRow), view.currency)
        return sheet
    }

    private fun RowWriter.writeExpenseRow(
        cursor: RowCursor,
        expense: TripExpenseExportDto,
        ctx: SheetContext,
    ): RowWriter {
        val (positionId, currency, labels) = ctx
        val (lineNumber, excelRow) = cursor
        val quantityCol = ExcelColumnLetters.of(Col.QUANTITY)
        val unitPriceCol = ExcelColumnLetters.of(Col.UNIT_PRICE_NET)
        return text("$positionId.$lineNumber")
            .text(labels.kindLabels[expense.kindLabelKey] ?: expense.kindLabelKey)
            .text(expense.description)
            .date(TripExportDates.parse(expense.travelDate))
            .quantity(expense.quantity)
            .text(expense.unitLabel)
            .money(expense.unitPriceNet, currency)
            .formulaMoney("$quantityCol$excelRow*$unitPriceCol$excelRow")
            .blank() // arrival/departure — expense rows carry no per-diem flags
            .blank() // breakfast deduction
            .blank() // lunch deduction
            .blank() // dinner deduction
            .blank() // control net — only the total row
            .blank() // Δ net — only the total row
    }

    /**
     * The total row is written via [SheetWriter.noteRow], not [SheetWriter.row]: it is a summary,
     * not one more expense/per-diem record, so [SheetWriter.recordCount] (the "Export" cover's own
     * per-sheet record count, R7) must count only the actual lines above it.
     *
     * An empty trip (border case — fixed price only, no expenses, no per-diem days) writes a plain
     * `0` rather than a `SUM` formula over an empty/inverted range: nothing to add up yet still a
     * genuine, correct total, without depending on how a spreadsheet engine normalises a reversed
     * `H2:H1`-style reference.
     */
    private fun writeTotalRow(sheet: SheetWriter, labels: TripExpenseSheetLabels, dataRows: DataRowRange, currency: Currency) {
        val netCol = ExcelColumnLetters.of(Col.NET_AMOUNT)
        val row = sheet.noteRow()
            .text(labels.totalRowLabel)
            .blank() // kind
            .blank() // description
            .blank() // date
            .blank() // quantity
            .blank() // unit
            .blank() // unit price net
        if (dataRows.last >= dataRows.first) {
            row.formulaMoney("SUM($netCol${dataRows.first}:$netCol${dataRows.last})")
        } else {
            row.money(Money.ZERO, currency)
        }
        row
            .blank() // arrival/departure
            .blank() // breakfast deduction
            .blank() // lunch deduction
            .blank() // dinner deduction
            // Per-diem lines live on the dedicated Verpflegung sheet. The overall trip total is
            // checked there; the Posten sheet deliberately has no cross-sheet delta row.
            .blank()
            .blank()
    }
}
