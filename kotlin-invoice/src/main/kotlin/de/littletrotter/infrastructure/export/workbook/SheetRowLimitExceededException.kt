package de.littletrotter.infrastructure.export.workbook

/**
 * Thrown instead of silently truncating a sheet once writing another row would exceed Excel's row
 * ceiling — a truncated file that still looks complete is worse than a loud failure.
 */
class SheetRowLimitExceededException(sheetName: String, attemptedRowIndex: Int) :
    RuntimeException(
        "Sheet '$sheetName' cannot hold a row at index $attemptedRowIndex " +
            "(Excel's limit is ${WorkbookLimits.MAX_ROWS_PER_SHEET} rows per sheet).",
    )
