package de.littletrotter.infrastructure.export.workbook

import de.littletrotter.shared.Currency
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * every style carries Arial (PO decision P4), body text at 12 pt, headings at 14 pt, and
 * the exact fill/font colours the design spec's own table declares. [ContrastRatioTest] guards the
 * same palette constants for WCAG contrast; this class only pins that each style actually uses
 * them.
 */
class CellStylesTest {

    @Test
    fun `header style is white bold 14 on the header fill, wrapped, vertically centred, with a Blue 800 bottom border`() {
        XSSFWorkbook().use { workbook ->
            val styles = CellStyles(workbook)
            val style = styles.header as XSSFCellStyle
            assertThat(style.font.fontName).isEqualTo("Arial")
            assertThat(style.font.fontHeightInPoints.toInt()).isEqualTo(14)
            assertThat(style.font.bold).isTrue()
            assertThat(style.font.getXSSFColor().argbHex).isEqualTo("FFFFFFFF")
            assertThat(style.fillPattern).isEqualTo(FillPatternType.SOLID_FOREGROUND)
            assertThat(style.fillForegroundXSSFColor.argbHex).isEqualTo("FF1967D2")
            assertThat(style.wrapText).isTrue()
            assertThat(style.borderBottom.name).isEqualTo("THIN")
            assertThat(style.bottomBorderXSSFColor.argbHex).isEqualTo("FF174EA6")
            assertThat(style.verticalAlignment.name).isEqualTo("CENTER")
        }
    }

    @Test
    fun `title style is 14 bold on lt-text-primary, with no fill`() {
        XSSFWorkbook().use { workbook ->
            val styles = CellStyles(workbook)
            val style = styles.title as XSSFCellStyle
            assertThat(style.font.fontName).isEqualTo("Arial")
            assertThat(style.font.fontHeightInPoints.toInt()).isEqualTo(14)
            assertThat(style.font.bold).isTrue()
            assertThat(style.font.getXSSFColor().argbHex).isEqualTo("FF202124")
            assertThat(style.fillPattern).isEqualTo(FillPatternType.NO_FILL)
        }
    }

    @Test
    fun `sectionHeading style is 14 bold on lt-text-primary, filled with the section fill`() {
        XSSFWorkbook().use { workbook ->
            val styles = CellStyles(workbook)
            val style = styles.sectionHeading as XSSFCellStyle
            assertThat(style.font.fontName).isEqualTo("Arial")
            assertThat(style.font.fontHeightInPoints.toInt()).isEqualTo(14)
            assertThat(style.font.bold).isTrue()
            assertThat(style.font.getXSSFColor().argbHex).isEqualTo("FF202124")
            assertThat(style.fillPattern).isEqualTo(FillPatternType.SOLID_FOREGROUND)
            assertThat(style.fillForegroundXSSFColor.argbHex).isEqualTo("FFE8F0FE")
        }
    }

    @Test
    fun `secondaryText style is 12 regular on lt-text-secondary, always wrapped`() {
        XSSFWorkbook().use { workbook ->
            val styles = CellStyles(workbook)
            val style = styles.secondaryText as XSSFCellStyle
            assertThat(style.font.fontName).isEqualTo("Arial")
            assertThat(style.font.fontHeightInPoints.toInt()).isEqualTo(12)
            assertThat(style.font.bold).isFalse()
            assertThat(style.font.getXSSFColor().argbHex).isEqualTo("FF5F6368")
            assertThat(style.wrapText).isTrue()
        }
    }

    @Test
    fun `every body style - text, numbers, boolean - carries Arial 12 regular on lt-text-primary`() {
        XSSFWorkbook().use { workbook ->
            val styles = CellStyles(workbook)
            listOf(
                styles.text, styles.wrappedText, styles.money, styles.quantity, styles.percent,
                styles.integer, styles.date, styles.dateTime, styles.boolStyle, styles.deviation, styles.deviationInteger,
            ).forEach { style ->
                val xssfStyle = style as XSSFCellStyle
                assertThat(xssfStyle.font.fontName).isEqualTo("Arial")
                assertThat(xssfStyle.font.fontHeightInPoints.toInt()).isEqualTo(12)
                assertThat(xssfStyle.font.bold).isFalse()
                assertThat(xssfStyle.font.getXSSFColor().argbHex).isEqualTo("FF202124")
            }
        }
    }

    @Test
    fun `wrappedText wraps, text does not`() {
        XSSFWorkbook().use { workbook ->
            val styles = CellStyles(workbook)
            assertThat(styles.text.wrapText).isFalse()
            assertThat(styles.wrappedText.wrapText).isTrue()
        }
    }

    @Test
    fun `deviation style carries only the money format - the highlight lives in a conditional-formatting rule now`() {
        XSSFWorkbook().use { workbook ->
            val styles = CellStyles(workbook)
            val style = styles.deviation
            assertThat(style.dataFormatString).isEqualTo(CellStyles.MONEY_FORMAT)
            // the fill/font highlight moved out of the static cell style into
            // DeviationConditionalFormatting, so an unremarkable zero looks unremarkable — see
            // that class's own test for the actual highlight assertions.
            assertThat(style.fillPattern).isEqualTo(FillPatternType.NO_FILL)
        }
    }

    @Test
    fun `deviationInteger style carries only the integer format - the highlight lives in a conditional-formatting rule now`() {
        XSSFWorkbook().use { workbook ->
            val styles = CellStyles(workbook)
            val style = styles.deviationInteger
            assertThat(style.dataFormatString).isEqualTo(CellStyles.INTEGER_FORMAT)
            assertThat(style.fillPattern).isEqualTo(FillPatternType.NO_FILL)
        }
    }

    @Test
    fun `numeric styles carry the documented format strings`() {
        XSSFWorkbook().use { workbook ->
            val styles = CellStyles(workbook)
            assertThat(styles.money.dataFormatString).isEqualTo("#,##0.00")
            assertThat(styles.quantity.dataFormatString).isEqualTo("#,##0.0000")
            assertThat(styles.percent.dataFormatString).isEqualTo("#,##0.00")
            assertThat(styles.integer.dataFormatString).isEqualTo("#,##0")
            assertThat(styles.date.dataFormatString).isEqualTo("DD.MM.YYYY")
            assertThat(styles.dateTime.dataFormatString).isEqualTo("DD.MM.YYYY HH:MM:SS")
        }
    }

    @Test
    fun `moneyForCurrency embeds the ISO code as a literal in the number format`() {
        XSSFWorkbook().use { workbook ->
            val styles = CellStyles(workbook)
            assertThat(styles.moneyForCurrency(Currency.EUR).dataFormatString).isEqualTo("#,##0.00 \"EUR\"")
            assertThat(styles.moneyForCurrency(Currency.CHF).dataFormatString).isEqualTo("#,##0.00 \"CHF\"")
        }
    }

    @Test
    fun `moneyForCurrency also carries Arial 12 regular, like every other body style`() {
        XSSFWorkbook().use { workbook ->
            val styles = CellStyles(workbook)
            val style = styles.moneyForCurrency(Currency.EUR) as XSSFCellStyle
            assertThat(style.font.fontName).isEqualTo("Arial")
            assertThat(style.font.fontHeightInPoints.toInt()).isEqualTo(12)
        }
    }

    @Test
    fun `moneyForCurrency caches one style per currency instead of creating a new one each call`() {
        XSSFWorkbook().use { workbook ->
            val styles = CellStyles(workbook)
            val before = workbook.numCellStyles
            val first = styles.moneyForCurrency(Currency.USD)
            val afterFirst = workbook.numCellStyles
            val second = styles.moneyForCurrency(Currency.USD)
            val afterSecond = workbook.numCellStyles

            assertThat(afterFirst).isEqualTo(before + 1)
            assertThat(afterSecond).isEqualTo(afterFirst)
            assertThat(second).isSameAs(first)
        }
    }

    @Test
    fun `moneyForCurrency never touches the currency-agnostic money style`() {
        XSSFWorkbook().use { workbook ->
            val styles = CellStyles(workbook)
            styles.moneyForCurrency(Currency.GBP)
            assertThat(styles.money.dataFormatString).isEqualTo(CellStyles.MONEY_FORMAT)
        }
    }

    @Test
    fun `each style is created exactly once per workbook`() {
        XSSFWorkbook().use { workbook ->
            CellStyles(workbook)
            // header, title, sectionHeading, secondaryText, text, wrappedText, money, quantity,
            // percent, integer, date, dateTime, boolStyle, deviation, deviationInteger = 15 styles,
            // plus the trip-detail secondaryMoney, secondaryQuantity, secondaryInteger, secondaryDate,
            // secondaryBool (the Positionen sheet's trip detail rows) = 20, plus the workbook's own
            // built-in default.
            assertThat(workbook.numCellStyles).isEqualTo(21)
        }
    }

    @Test
    fun `every RK-6 secondary numeric style carries Arial 12 regular on lt-text-secondary, same formats as their ordinary counterparts`() {
        XSSFWorkbook().use { workbook ->
            val styles = CellStyles(workbook)
            listOf(
                styles.secondaryMoney to CellStyles.MONEY_FORMAT,
                styles.secondaryQuantity to CellStyles.QUANTITY_FORMAT,
                styles.secondaryInteger to CellStyles.INTEGER_FORMAT,
                styles.secondaryDate to CellStyles.DATE_FORMAT,
            ).forEach { (style, format) ->
                val xssfStyle = style as XSSFCellStyle
                assertThat(xssfStyle.font.fontName).isEqualTo("Arial")
                assertThat(xssfStyle.font.fontHeightInPoints.toInt()).isEqualTo(12)
                assertThat(xssfStyle.font.bold).isFalse()
                assertThat(xssfStyle.font.getXSSFColor().argbHex).isEqualTo("FF5F6368")
                assertThat(xssfStyle.dataFormatString).isEqualTo(format)
            }
            assertThat((styles.secondaryBool as XSSFCellStyle).font.getXSSFColor().argbHex).isEqualTo("FF5F6368")
        }
    }

    @Test
    fun `secondaryMoneyForCurrency is cached per currency, same as moneyForCurrency, and never touches secondaryMoney itself`() {
        XSSFWorkbook().use { workbook ->
            val styles = CellStyles(workbook)
            val before = workbook.numCellStyles
            val first = styles.secondaryMoneyForCurrency(Currency.USD)
            val afterFirst = workbook.numCellStyles
            val second = styles.secondaryMoneyForCurrency(Currency.USD)

            assertThat(afterFirst).isEqualTo(before + 1)
            assertThat(workbook.numCellStyles).isEqualTo(afterFirst)
            assertThat(second).isSameAs(first)
            assertThat((first as XSSFCellStyle).font.getXSSFColor().argbHex).isEqualTo("FF5F6368")
            assertThat(styles.secondaryMoney.dataFormatString).isEqualTo(CellStyles.MONEY_FORMAT)
        }
    }
}
