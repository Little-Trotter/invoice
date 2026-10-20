package de.littletrotter.infrastructure.export.workbook

/** Excel's hard row ceiling and the guard that enforces it. */
internal object WorkbookLimits {

    const val MAX_ROWS_PER_SHEET = 1_048_576

    fun requireRowIndexInBounds(rowIndex: Int, sheetName: String) {
        if (rowIndex >= MAX_ROWS_PER_SHEET) {
            throw SheetRowLimitExceededException(sheetName, rowIndex)
        }
    }
}
