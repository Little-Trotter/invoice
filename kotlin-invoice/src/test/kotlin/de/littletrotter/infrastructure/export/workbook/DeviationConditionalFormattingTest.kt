package de.littletrotter.infrastructure.export.workbook

import org.apache.poi.xssf.usermodel.XSSFColor
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * §3 — [DeviationConditionalFormatting] built and read back with real POI, the same
 * "read the workbook back" discipline every other writer test in this package follows: asserting
 * that a setter was called proves nothing about what Excel will actually show.
 */
class DeviationConditionalFormattingTest {

    private val fillRgb = byteArrayOf(0xFF.toByte(), 0xEB.toByte(), 0xEE.toByte())
    private val fontRgb = byteArrayOf(0xB7.toByte(), 0x1C.toByte(), 0x1C.toByte())

    @Test
    fun `applies one rule per column with the Material Red 50 fill and Red 900 font`() {
        XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet("Sheet")
            DeviationConditionalFormatting.apply(sheet, listOf(1, 3), firstDataRow = 1, lastDataRow = 5)

            assertThat(sheet.sheetConditionalFormatting.numConditionalFormattings).isEqualTo(2)
            (0 until sheet.sheetConditionalFormatting.numConditionalFormattings).forEach { index ->
                val formatting = sheet.sheetConditionalFormatting.getConditionalFormattingAt(index)
                assertThat(formatting.numberOfRules).isEqualTo(1)
                val rule = formatting.getRule(0)
                assertThat((rule.patternFormatting.fillForegroundColorColor as XSSFColor).rgb).isEqualTo(fillRgb)
                assertThat((rule.fontFormatting.fontColor as XSSFColor).rgb).isEqualTo(fontRgb)
            }
        }
    }

    @Test
    fun `each rule's region matches its own column across the given data-row range`() {
        XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet("Sheet")
            DeviationConditionalFormatting.apply(sheet, listOf(2, 4), firstDataRow = 1, lastDataRow = 100)

            val regions = (0 until sheet.sheetConditionalFormatting.numConditionalFormattings)
                .map { sheet.sheetConditionalFormatting.getConditionalFormattingAt(it).formattingRanges.single() }

            assertThat(regions.map { it.firstColumn to it.lastColumn }).containsExactlyInAnyOrder(2 to 2, 4 to 4)
            regions.forEach {
                assertThat(it.firstRow).isEqualTo(1)
                assertThat(it.lastRow).isEqualTo(100)
            }
        }
    }

    @Test
    fun `the rule formula checks the absolute value of the column's own first data row against 0,005`() {
        XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet("Sheet")
            DeviationConditionalFormatting.apply(sheet, listOf(1), firstDataRow = 1, lastDataRow = 10)

            val rule = sheet.sheetConditionalFormatting.getConditionalFormattingAt(0).getRule(0)
            assertThat(rule.formula1).isEqualTo("ABS(B2)>0.005")
        }
    }

    @Test
    fun `an empty column list applies no rule`() {
        XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet("Sheet")
            DeviationConditionalFormatting.apply(sheet, emptyList(), firstDataRow = 1, lastDataRow = 10)
            assertThat(sheet.sheetConditionalFormatting.numConditionalFormattings).isZero()
        }
    }

    @Test
    fun `a sheet with no data rows (lastDataRow before firstDataRow) applies no rule`() {
        XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet("Sheet")
            DeviationConditionalFormatting.apply(sheet, listOf(1), firstDataRow = 1, lastDataRow = 0)
            assertThat(sheet.sheetConditionalFormatting.numConditionalFormattings).isZero()
        }
    }
}
