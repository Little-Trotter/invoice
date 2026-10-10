package de.littletrotter.infrastructure.export.workbook

import de.littletrotter.shared.Currency
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.Font
import org.apache.poi.ss.usermodel.VerticalAlignment
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFColor
import org.apache.poi.xssf.usermodel.XSSFFont

/**
 * Every [CellStyle] this package writes with, built exactly once per [Workbook] and reused from
 * then on. POI caps a workbook at 64 000 cell styles; creating one per row (or per cell) instead
 * of reusing these would hit that ceiling somewhere north of 60 000 rows, exactly the size nobody
 * is watching for it.
 *
 * The workbook's own design: Arial everywhere (present on Windows, macOS, LibreOffice and
 * Google Sheets; POI's own default is Calibri 11, so the family is set on every font this class
 * creates, never left implicit), body text at 12 pt, headings (the header row, the "Export" sheet's
 * title and section headings) at 14 pt. Every fill/font pair below meets WCAG AA's 4.5:1 contrast
 * floor ([Palette]/ContrastRatioTest guard the actual RGB values against a future "just make it
 * lighter"). Colours are set via [XSSFColor] rather than [org.apache.poi.ss.usermodel.IndexedColors]:
 * a [Workbook] created by [org.apache.poi.xssf.streaming.SXSSFWorkbook.createCellStyle] still
 * returns a genuine [XSSFCellStyle] underneath (SXSSF only streams *rows*, never styles), so the
 * cast below is always safe for the two [Workbook] implementations this package ever runs against
 * ([org.apache.poi.xssf.usermodel.XSSFWorkbook] directly, in tests, and
 * [org.apache.poi.xssf.streaming.SXSSFWorkbook] in production).
 *
 * The money/quantity/percent/integer formats carry no currency symbol and no `%` sign on purpose:
 * the workbook can carry several currencies in one sheet (a hard-coded `€` would lie about CHF
 * rows), and a tax rate is stored as the plain number `19`, which a `%` format would render as
 * `1900 %`.
 */
internal class CellStyles(private val workbook: Workbook) {

    // Fonts are built once and shared by every style that uses the same face/size/colour — POI
    // does not cap fonts as tightly as cell styles, but there is no reason to mint a dozen
    // identical "Arial 12 #202124" font objects when one will do.
    private val bodyFont: Font = font(size = Palette.BODY_FONT_SIZE, bold = false, rgb = Palette.TEXT_PRIMARY_RGB)
    private val headerFont: Font = font(size = Palette.HEADING_FONT_SIZE, bold = true, rgb = Palette.WHITE_RGB)
    private val headingFont: Font = font(size = Palette.HEADING_FONT_SIZE, bold = true, rgb = Palette.TEXT_PRIMARY_RGB)
    private val secondaryFont: Font = font(size = Palette.BODY_FONT_SIZE, bold = false, rgb = Palette.TEXT_SECONDARY_RGB)

    /** The column header row of every sheet: white 14 bold on Blue 700 (`#1967D2`), wrapped,
     *  vertically centred, with a thin Blue 800 (`#174EA6`) bottom border. */
    val header: CellStyle = headerStyle()

    /** The "Export" sheet's own title row (A1, the product line) — 14 bold, no fill. */
    val title: CellStyle = titleStyle()

    /** A section heading inside the "Export" sheet's cover layout — 14 bold on Blue 50
     *  (`#E8F0FE`). */
    val sectionHeading: CellStyle = sectionHeadingStyle()

    /** Secondary/explanatory text (the "Export" sheet's subtitle and notes) — 12 regular,
     *  `#5F6368`, always wrapped. */
    val secondaryText: CellStyle = secondaryTextStyle()

    val text: CellStyle = textStyle(wrap = false)
    val wrappedText: CellStyle = textStyle(wrap = true)
    val money: CellStyle = numberStyle(MONEY_FORMAT)
    val quantity: CellStyle = numberStyle(QUANTITY_FORMAT)
    val percent: CellStyle = numberStyle(PERCENT_FORMAT)
    val integer: CellStyle = numberStyle(INTEGER_FORMAT)
    val date: CellStyle = numberStyle(DATE_FORMAT)
    val dateTime: CellStyle = numberStyle(DATE_TIME_FORMAT)
    val boolStyle: CellStyle = bodyStyle()

    // ── The `Positionen` sheet's own trip detail (level-1) rows, in the muted
    // `secondaryText` colour rather than the ordinary body colour, so a sub-line reads as
    // subordinate to the position row above it at a glance. Same formats as their ordinary
    // counterparts above, only the font differs.
    val secondaryMoney: CellStyle = numberStyle(MONEY_FORMAT, secondaryFont)
    val secondaryQuantity: CellStyle = numberStyle(QUANTITY_FORMAT, secondaryFont)
    val secondaryInteger: CellStyle = numberStyle(INTEGER_FORMAT, secondaryFont)
    val secondaryDate: CellStyle = numberStyle(DATE_FORMAT, secondaryFont)
    val secondaryBool: CellStyle = workbook.createCellStyle().apply { setFont(secondaryFont) }

    /** As [moneyForCurrency], styled with [secondaryFont] — a trip detail row's own `unitPriceNet`/
     *  `netAmount` cells. */
    fun secondaryMoneyForCurrency(currency: Currency): CellStyle =
        secondaryMoneyByCurrency.getOrPut(currency) { numberStyle(moneyFormatFor(currency), secondaryFont) }

    private val secondaryMoneyByCurrency = mutableMapOf<Currency, CellStyle>()

    /**
     * Money-formatted, for the deviation ("Δ") columns placed next to every
     * control-formula column (R4). The highlight itself — Material Red 50 fill, Red 900 text —
     * is deliberately **not** part of this style any more: a cell style is static regardless of
     * the value it holds, which is exactly what made the first cut wrong (every Δ cell looked
     * alarming, whatever it held, so nothing stood out). [DeviationConditionalFormatting] applies
     * the highlight as a genuine Excel conditional-formatting rule instead, evaluated per cell
     * against the 0.005 tolerance — this style only ever supplies the number format underneath.
     */
    val deviation: CellStyle = numberStyle(MONEY_FORMAT)

    /** As [deviation], integer-formatted — for a deviation column whose control formula is a
     *  `COUNTIF` (the "Δ Positionen" column), not a monetary `SUMIF`. */
    val deviationInteger: CellStyle = numberStyle(INTEGER_FORMAT)

    /**
     * R7 — a money cell that also names its ISO currency, right in the cell's own number format
     * (`#,##0.00 "EUR"`), so a printed/opened workbook reads exactly like the UI's `1.234,56 EUR`
     * — while the cell itself stays a genuine number, because that is the one property this whole
     * export exists to guarantee: a reader must be able to sum it, and Excel does not sum text.
     * Cached per [Currency] rather than built fresh per cell — POI caps a workbook at 64 000 cell
     * styles, and this export only ever needs at most six (one per [Currency] value).
     */
    fun moneyForCurrency(currency: Currency): CellStyle =
        moneyByCurrency.getOrPut(currency) { numberStyle(moneyFormatFor(currency)) }

    private val moneyByCurrency = mutableMapOf<Currency, CellStyle>()

    /** The ISO code is always three plain ASCII letters (ArchUnit/[Currency] itself guarantee
     *  this), so it needs no escaping beyond the quotes Excel's own format-string syntax requires
     *  around a literal. */
    private fun moneyFormatFor(currency: Currency): String = "$MONEY_FORMAT \"${currency.code}\""

    private fun headerStyle(): CellStyle = (workbook.createCellStyle() as XSSFCellStyle).apply {
        setFont(headerFont)
        setFillForegroundColor(XSSFColor(Palette.HEADER_FILL_RGB))
        fillPattern = FillPatternType.SOLID_FOREGROUND
        wrapText = true
        borderBottom = BorderStyle.THIN
        setBottomBorderColor(XSSFColor(Palette.HEADER_BORDER_RGB))
        verticalAlignment = VerticalAlignment.CENTER
    }

    private fun titleStyle(): CellStyle = workbook.createCellStyle().apply { setFont(headingFont) }

    private fun sectionHeadingStyle(): CellStyle = workbook.createCellStyle().apply {
        setFont(headingFont)
        setFillForegroundColor(XSSFColor(Palette.SECTION_FILL_RGB))
        fillPattern = FillPatternType.SOLID_FOREGROUND
    }

    private fun secondaryTextStyle(): CellStyle = workbook.createCellStyle().apply {
        setFont(secondaryFont)
        wrapText = true
    }

    private fun bodyStyle(): CellStyle = workbook.createCellStyle().apply { setFont(bodyFont) }

    private fun textStyle(wrap: Boolean): CellStyle = workbook.createCellStyle().apply {
        setFont(bodyFont)
        wrapText = wrap
    }

    private fun numberStyle(format: String, font: Font = bodyFont): CellStyle = workbook.createCellStyle().apply {
        setFont(font)
        dataFormat = workbook.createDataFormat().getFormat(format)
    }

    private fun font(size: Int, bold: Boolean, rgb: ByteArray): Font =
        (workbook.createFont() as XSSFFont).apply {
            fontName = Palette.FONT_FAMILY
            fontHeightInPoints = size.toShort()
            this.bold = bold
            setColor(XSSFColor(rgb))
        }

    companion object {
        const val MONEY_FORMAT = "#,##0.00"
        const val QUANTITY_FORMAT = "#,##0.0000"
        const val PERCENT_FORMAT = "#,##0.00"
        const val INTEGER_FORMAT = "#,##0"
        const val DATE_FORMAT = "DD.MM.YYYY"
        const val DATE_TIME_FORMAT = "DD.MM.YYYY HH:MM:SS"
    }
}
