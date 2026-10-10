package de.littletrotter.infrastructure.export.workbook

import de.littletrotter.shared.Currency
import de.littletrotter.shared.GermanTime
import de.littletrotter.shared.Money
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.Row
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Writes one row, one typed cell at a time. Deliberately no generic `cell(Any?)` overload — every
 * cell kind has its own method, and a `null` argument always produces a genuinely blank cell,
 * never the string `"null"` and never `0`.
 *
 * Each method appends the next cell in the row and returns `this`, so a row is built by chaining:
 * `sheet.row().text("A").money(amount).date(dueDate)`.
 */
class RowWriter internal constructor(
    private val row: Row,
    private val styles: CellStyles,
    private val onColumnWritten: (Int) -> Unit,
) {
    private var columnIndex = 0

    /** [wrap] selects [CellStyles.wrappedText] over [CellStyles.text] for long free text;
     *  [secondary] (a trip detail row) selects [CellStyles.secondaryText] over either,
     *  taking priority since a detail row's own free text still wraps. */
    fun text(value: String?, wrap: Boolean = false, secondary: Boolean = false): RowWriter =
        writeOrBlank(value, if (secondary) styles.secondaryText else if (wrap) styles.wrappedText else styles.text) { cell, v ->
            cell.setCellValue(TextGuard.sanitize(v))
        }

    /** [CellStyles.title] — the "Export" sheet's own title row (A1, the product line). */
    fun title(value: String?): RowWriter =
        writeOrBlank(value, styles.title) { cell, v -> cell.setCellValue(TextGuard.sanitize(v)) }

    /** [CellStyles.sectionHeading] — a section header inside the "Export" sheet's cover layout. */
    fun section(value: String?): RowWriter =
        writeOrBlank(value, styles.sectionHeading) { cell, v -> cell.setCellValue(TextGuard.sanitize(v)) }

    /** [CellStyles.secondaryText] — explanatory/secondary text; the style itself always wraps. */
    fun secondary(value: String?): RowWriter =
        writeOrBlank(value, styles.secondaryText) { cell, v -> cell.setCellValue(TextGuard.sanitize(v)) }

    fun money(value: Money?, secondary: Boolean = false): RowWriter =
        writeOrBlank(value, if (secondary) styles.secondaryMoney else styles.money) { cell, v -> cell.setCellValue(v.amount.toDouble()) }

    /** R7 — as [money], but the cell's own number format names [currency]'s ISO code (never the
     *  glyph, and never assumed to be Euro), so the cell reads exactly like the UI's
     *  `1.234,56 EUR` while staying a genuine, summable number. [secondary] is a trip
     *  detail row's own muted style. */
    fun money(value: Money?, currency: Currency, secondary: Boolean = false): RowWriter =
        writeOrBlank(value, if (secondary) styles.secondaryMoneyForCurrency(currency) else styles.moneyForCurrency(currency)) { cell, v ->
            cell.setCellValue(v.amount.toDouble())
        }

    fun quantity(value: BigDecimal?, secondary: Boolean = false): RowWriter =
        writeOrBlank(value, if (secondary) styles.secondaryQuantity else styles.quantity) { cell, v -> cell.setCellValue(v.toDouble()) }

    /** [value] is the plain percentage number (`19` for 19 %), never pre-divided by 100 — see
     *  [CellStyles]'s own KDoc for why the cell format is a plain number, not `%`. */
    fun percent(value: BigDecimal?): RowWriter =
        writeOrBlank(value, styles.percent) { cell, v -> cell.setCellValue(v.toDouble()) }

    fun integer(value: Long?, secondary: Boolean = false): RowWriter =
        writeOrBlank(value, if (secondary) styles.secondaryInteger else styles.integer) { cell, v -> cell.setCellValue(v.toDouble()) }

    fun date(value: LocalDate?, secondary: Boolean = false): RowWriter =
        writeOrBlank(value, if (secondary) styles.secondaryDate else styles.date) { cell, v -> cell.setCellValue(v) }

    /** Converted to the German wall-clock time ([GermanTime.ZONE]) before writing — an
     *  [OffsetDateTime] is a point in time, and this cell is meant to be read by a human. */
    fun dateTime(value: OffsetDateTime?): RowWriter =
        writeOrBlank(value, styles.dateTime) { cell, v ->
            cell.setCellValue(v.atZoneSameInstant(GermanTime.ZONE).toLocalDateTime())
        }

    fun bool(value: Boolean?, secondary: Boolean = false): RowWriter =
        writeOrBlank(value, if (secondary) styles.secondaryBool else styles.boolStyle) { cell, v -> cell.setCellValue(v) }

    /** Sets a formula cell with no particular number format — for a formula whose own result is
     *  text, such as the "Prüfung" verdict column (`IF(...; "Abweichung"; "OK")`). */
    fun formula(formula: String): RowWriter {
        nextCell().cellFormula = formula
        advance()
        return this
    }

    /** A formula cell styled as money (the `Kontrolle Netto/Steuer/Brutto` columns). */
    fun formulaMoney(formula: String): RowWriter = formulaStyled(formula, styles.money)

    /** A formula cell styled as a plain integer (the `Kontrolle Positionen` column). */
    fun formulaInteger(formula: String): RowWriter = formulaStyled(formula, styles.integer)

    /** A formula cell styled with [CellStyles.deviation] — R4's highlighted `Δ`-column next to a
     *  monetary control formula. */
    fun formulaDeviation(formula: String): RowWriter = formulaStyled(formula, styles.deviation)

    /** As [formulaDeviation], integer-formatted — R4's `Δ Positionen` column. */
    fun formulaDeviationInteger(formula: String): RowWriter = formulaStyled(formula, styles.deviationInteger)

    private fun formulaStyled(formula: String, style: CellStyle): RowWriter {
        val cell = nextCell()
        cell.cellFormula = formula
        cell.cellStyle = style
        advance()
        return this
    }

    fun blank(): RowWriter {
        nextCell()
        advance()
        return this
    }

    private fun <T> writeOrBlank(value: T?, style: CellStyle, set: (Cell, T) -> Unit): RowWriter {
        val cell = nextCell()
        if (value == null) {
            cell.setBlank()
        } else {
            set(cell, value)
            cell.cellStyle = style
        }
        advance()
        return this
    }

    private fun nextCell(): Cell = row.createCell(columnIndex)

    private fun advance() {
        onColumnWritten(columnIndex)
        columnIndex++
    }
}
