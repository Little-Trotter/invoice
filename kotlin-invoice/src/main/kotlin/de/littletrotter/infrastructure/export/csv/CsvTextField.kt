package de.littletrotter.infrastructure.export.csv

import de.littletrotter.infrastructure.export.workbook.TextGuard

/**
 * Renders one text field of the CSV export, in the same three steps a text cell of the
 * `Positionen` sheet goes through, plus the one step that is CSV-only:
 *
 * 1. [TextGuard.sanitize] — the exact same sanitisation [de.littletrotter.infrastructure.export
 *    .workbook.RowWriter.text] applies before writing an XLSX cell (control characters other than
 *    tab/newline stripped, lone surrogates replaced, the OOXML 32,767-character cap enforced).
 *    Running CSV text through the identical step is what keeps the two formats from ever
 *    disagreeing about a value — including on the truncation and control-character border cases,
 *    not only the ordinary ones.
 * 2. [CsvFormulaGuard.neutralize] — CSV-only: XLSX never reaches [org.apache.poi.ss.usermodel.Cell
 *    .setCellFormula] from a text value, so it carries no injection risk in the first place; CSV
 *    has no such structural protection, so this step supplies it explicitly.
 * 3. [CsvFieldEscaper.escape] — RFC 4180 quoting for the delimiter, `"` and line breaks.
 *
 * A `null` value renders as an empty field — never the string `"null"` — and skips all three
 * steps, matching a blank XLSX cell.
 */
internal object CsvTextField {

    fun render(value: String?): String {
        if (value == null) return ""
        val sanitized = TextGuard.sanitize(value)
        val neutralized = CsvFormulaGuard.neutralize(sanitized)
        return CsvFieldEscaper.escape(neutralized)
    }
}
