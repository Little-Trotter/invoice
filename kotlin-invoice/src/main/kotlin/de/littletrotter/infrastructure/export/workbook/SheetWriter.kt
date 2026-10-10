package de.littletrotter.infrastructure.export.workbook

import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.util.CellRangeAddress

/**
 * Writes one sheet: a header row, then any number of data rows. On [finish] it freezes the header
 * row, switches on the autofilter over the whole used range, and applies the column widths
 * declared in [header] — never `autoSizeColumn`, which SXSSF cannot use correctly because it only
 * sees the rows still held in its in-memory window, not the ones already flushed to disk.
 */
class SheetWriter internal constructor(
    private val sheet: Sheet,
    private val styles: CellStyles,
) {
    /** The sanitised name POI actually gave this sheet — see [SheetNames]. */
    val name: String get() = sheet.sheetName

    /** Number of data rows written via [row], excluding the header row. Lets a caller fill in the
     *  "Export" sheet's per-sheet record counts once every data sheet has been written. */
    var recordCount: Long = 0
        private set

    private var nextRowIndex = 0
    private var lastColumnIndex = -1
    private var columnWidths: List<Int> = emptyList()
    private var headerWritten = false
    private var deviationColumnIndexes: List<Int> = emptyList()
    private var deviationCells: List<Pair<Int, Int>> = emptyList()

    /**
     * [columnWidths] are given in whole characters (POI's own unit is 1/256th of one) — one entry
     * per label, strictly: a missing width is a build-time bug rather than a
     * silent default, since every column left at Excel's own 8.43-character default is exactly how
     * the workbook shipped unreadable in the first place (`#####` money, five-line headers). The
     * header row is fixed at two lines of 14 pt ([Palette.HEADER_ROW_HEIGHT_POINTS]) so a
     * wrapped two-line label never stacks any higher.
     */
    fun header(labels: List<String>, columnWidths: List<Int>): SheetWriter {
        require(columnWidths.size == labels.size) {
            "columnWidths must have exactly one entry per label (${labels.size}), got ${columnWidths.size}"
        }
        val poiRow = sheet.createRow(nextRowIndex++)
        poiRow.heightInPoints = Palette.HEADER_ROW_HEIGHT_POINTS
        labels.forEachIndexed { index, label ->
            val cell = poiRow.createCell(index)
            cell.setCellValue(TextGuard.sanitize(label))
            cell.cellStyle = styles.header
        }
        trackColumn(labels.size - 1)
        this.columnWidths = columnWidths
        headerWritten = true
        return this
    }

    /**
     * Declares this sheet's column widths without writing a tabular header row — for a cover
     * sheet like "Export", whose first row is a title, not column headers, so [header]'s own
     * side effects on [finish] (freeze pane, autofilter) would be meaningless, or actively wrong
     * (a filter arrow sitting on the title cell).
     */
    fun columnWidths(widths: List<Int>): SheetWriter {
        this.columnWidths = widths
        return this
    }

    /**
     * A sub-table header inside a cover sheet — same look as [header] (white bold on the header
     * fill) but none of its sheet-wide side effects ([finish]'s freeze pane, autofilter and
     * column-width declaration only make sense for a sheet's own single, genuine tabular header).
     * Used by the "Export" sheet's own "Field"/"Value" row, which sits partway down the sheet, not
     * at row 1.
     */
    fun subHeader(labels: List<String>): SheetWriter {
        val poiRow = sheet.createRow(nextRowIndex++)
        labels.forEachIndexed { index, label ->
            val cell = poiRow.createCell(index)
            cell.setCellValue(TextGuard.sanitize(label))
            cell.cellStyle = styles.header
        }
        return this
    }

    fun row(): RowWriter = createRow(countsAsRecord = true)

    /**
     * Writes a row that documents a data sheet's own absence of data (the "no journal entries"
     * case) rather than a data row itself. Unlike [row], this never counts toward [recordCount]:
     * the "Export" sheet's own per-sheet record counts (R7) must still say zero records when the
     * only thing on a sheet is a note explaining why it is empty.
     */
    fun noteRow(): RowWriter = createRow(countsAsRecord = false)

    private fun createRow(countsAsRecord: Boolean): RowWriter {
        WorkbookLimits.requireRowIndexInBounds(nextRowIndex, name)
        val poiRow = sheet.createRow(nextRowIndex++)
        if (countsAsRecord) recordCount++
        return RowWriter(poiRow, styles) { columnIndex -> trackColumn(columnIndex) }
    }

    private fun trackColumn(columnIndex: Int) {
        if (columnIndex > lastColumnIndex) lastColumnIndex = columnIndex
    }

    /**
     * Marks [columnIndexes] (0-based) as R4 deviation ("Δ") columns: [finish] applies
     * [DeviationConditionalFormatting]'s shared Material-Red rule to them once the sheet's actual
     * row count is known. Declared once per sheet — same call shape as [header]'s
     * [columnWidths] — rather than per row, since the rule covers the whole data range at once.
     */
    fun markDeviationColumns(columnIndexes: List<Int>): SheetWriter {
        deviationColumnIndexes = columnIndexes
        return this
    }

    /**
     * As [markDeviationColumns], but for exactly one cell (0-based row/column) rather than a whole
     * column across the sheet's data range — for a key/value sheet (the trip "Dienstreise"
     * sheet) whose Δ value is a single row among many unrelated ones, not a repeating per-record
     * column. Reuses the same [DeviationConditionalFormatting] rule, just scoped to a one-row
     * region so it never evaluates against an unrelated row's own value.
     */
    fun markDeviationCell(rowIndex: Int, columnIndex: Int): SheetWriter {
        deviationCells = deviationCells + (rowIndex to columnIndex)
        return this
    }

    /**
     * Called once by [WorkbookWriter.write]. Column widths are applied whether or not [header]
     * was ever called (a [columnWidths]-only cover sheet still needs its columns readable); freeze
     * pane, autofilter and the deviation highlight are a genuine tabular header's own side effects
     * and stay a no-op when [header] was never called — there is nothing sensible to freeze or
     * filter on an unstarted sheet.
     */
    internal fun finish() {
        columnWidths.forEachIndexed { index, width -> sheet.setColumnWidth(index, width * CHARACTER_WIDTH_UNITS) }
        if (!headerWritten) return
        sheet.createFreezePane(0, 1)
        val lastRow = maxOf(nextRowIndex - 1, 0)
        val lastColumn = maxOf(lastColumnIndex, 0)
        sheet.setAutoFilter(CellRangeAddress(0, lastRow, 0, lastColumn))
        DeviationConditionalFormatting.apply(sheet, deviationColumnIndexes, firstDataRow = FIRST_DATA_ROW_INDEX, lastDataRow = lastRow)
        deviationCells.forEach { (rowIndex, columnIndex) ->
            DeviationConditionalFormatting.apply(sheet, listOf(columnIndex), firstDataRow = rowIndex, lastDataRow = rowIndex)
        }
    }

    private companion object {
        const val CHARACTER_WIDTH_UNITS = 256

        /** 0-based row index of the first data row — row 0 is always the header. */
        const val FIRST_DATA_ROW_INDEX = 1
    }
}
