package de.littletrotter.infrastructure.export.csv

/**
 * The CSV export's one security requirement. A CSV cell whose text begins with `=`, `+`, `-`, `@`, a tab or
 * a carriage return is executed as a formula when the file is opened in Excel or Calc — invoice
 * descriptions, free text, cost centres and article numbers are all user-supplied, so this is a
 * live path from "somebody typed a description" to "code runs on the accountant's machine".
 *
 * The fix is the standard one: prefix a single apostrophe when (and only when) the *first*
 * character is one of the trigger characters. `Rabatt -10 %` is untouched — the minus is not
 * leading — while `=1+1`, `+1`, `-1` and `@SUM(A1)` all become e.g. `'=1+1`, read back as plain
 * text by every reader.
 *
 * Numeric, date and boolean columns never call this: they are produced by
 * [InvoicePositionCsvWriter]'s own formatters and can never carry raw user input, so a negative
 * amount keeps its leading minus untouched (there is no equivalent guard on that path, on purpose).
 *
 * The XLSX side needs no counterpart because [de.littletrotter.infrastructure.export.workbook
 * .RowWriter.text] only ever creates string cells, never [org.apache.poi.ss.usermodel.Cell
 * .setCellFormula] — a dedicated unit test in that package pins exactly this, so the XLSX format
 * stays safe without needing this guard at all.
 */
internal object CsvFormulaGuard {

    private const val APOSTROPHE = "'"
    private val TRIGGER_CHARACTERS = charArrayOf('=', '+', '-', '@', '\t', '\r')

    /** [value] must already be non-empty; an empty string has no first character to inspect. */
    fun neutralize(value: String): String =
        if (value.isNotEmpty() && value[0] in TRIGGER_CHARACTERS) APOSTROPHE + value else value
}
