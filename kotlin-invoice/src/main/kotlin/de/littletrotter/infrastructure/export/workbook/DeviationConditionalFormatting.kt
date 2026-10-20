package de.littletrotter.infrastructure.export.workbook

import org.apache.poi.ss.usermodel.PatternFormatting
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFColor

/**
 * The one Material-Red deviation highlight, built once and applied to the Δ columns of
 * both data sheets (`Rechnungen` and `Positionen`, this package). The `Rechnungen` sheet shipped its Δ
 * columns with a *static* [CellStyles.deviation] fill: every cell looked the same whatever its
 * value, which is not what R4 asks for ("a difference ≠ 0 is a finding and is optically
 * highlighted" — if every cell alarms, none does). A genuine Excel conditional-formatting rule
 * fixes that: the fill only paints a cell whose own value exceeds the tolerance.
 *
 * Colour is never the only signal for a finding on either sheet — the `Prüfung` text-verdict
 * column carries the same information for a reader who cannot distinguish the fill, or who prints
 * the sheet in greyscale; this class only ever supplies the fill/font, never text.
 */
internal object DeviationConditionalFormatting {

    /** The control-formula block's own tolerance (the `Prüfung` formula uses the same
     *  literal) — a binary-representation slack for a decimal amount, not a forgiven cent. */
    private const val THRESHOLD = "0.005"

    /** Material Red 50 (`#FFEBEE`) — fill. `internal`, not `private`: ContrastRatioTest reuses
     *  this exact constant rather than duplicating the literal. */
    internal val FILL_RGB = byteArrayOf(0xFF.toByte(), 0xEB.toByte(), 0xEE.toByte())

    /** Material Red 900 (`#B71C1C`) — font colour. The pair clears WCAG's 4.5:1 contrast floor by
     *  a wide margin. Not brown, per the project's colour guidance. */
    internal val FONT_RGB = byteArrayOf(0xB7.toByte(), 0x1C.toByte(), 0x1C.toByte())

    /**
     * Applies the rule to every column in [columnIndexes] (0-based) across
     * [firstDataRow]..[lastDataRow] (0-based row indices) of [sheet]. One region and rule per
     * column: the formula anchors on that column's own first data row with a relative (not
     * absolute) row reference, so POI/Excel reinterpret it relative to whichever cell in the
     * region is actually being evaluated — the same mechanism a hand-built Excel conditional
     * format uses when applied to a multi-row range.
     *
     * A no-op when there is nothing to highlight ([columnIndexes] empty, or [lastDataRow] before
     * [firstDataRow] — an empty sheet with only a header row) — a formatting rule anchored on a
     * region that does not exist would be dead weight in the file.
     */
    fun apply(sheet: Sheet, columnIndexes: List<Int>, firstDataRow: Int, lastDataRow: Int) {
        if (columnIndexes.isEmpty() || lastDataRow < firstDataRow) return
        val sheetFormatting = sheet.sheetConditionalFormatting
        val anchorRow = firstDataRow + 1 // Excel is 1-based; the formula names the region's own first row.
        columnIndexes.forEach { columnIndex ->
            val columnLetter = ExcelColumnLetters.of(columnIndex)
            val rule = sheetFormatting.createConditionalFormattingRule("ABS($columnLetter$anchorRow)>$THRESHOLD")
            rule.createPatternFormatting().apply {
                setFillForegroundColor(XSSFColor(FILL_RGB))
                fillPattern = PatternFormatting.SOLID_FOREGROUND
            }
            rule.createFontFormatting().fontColor = XSSFColor(FONT_RGB)
            val region = arrayOf(CellRangeAddress(firstDataRow, lastDataRow, columnIndex, columnIndex))
            sheetFormatting.addConditionalFormatting(region, rule)
        }
    }
}
