package de.littletrotter.infrastructure.export.workbook

import org.apache.poi.ss.usermodel.CellType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * 's one XLSX-side security pin: [RowWriter.text] must never reach
 * [org.apache.poi.ss.usermodel.Cell.setCellFormula], for any input — including a value shaped
 * exactly like a formula. That is why the XLSX export needs no counterpart to
 * [de.littletrotter.infrastructure.export.csv.CsvFormulaGuard]: as long as this test stays green,
 * an XLSX text cell can never execute what it holds, whatever a user typed into a description,
 * cost centre or article number.
 */
class RowWriterTextNeverReachesFormulaTest {

    @Test
    fun `a value shaped exactly like a formula still writes as a plain string cell, never a formula cell`() {
        val bytes = writeWorkbook { writer ->
            val sheet = writer.sheet("Data")
            sheet.header(listOf("Col"), listOf(8))
            sheet.row().text("=1+1")
        }
        readWorkbook(bytes).use { workbook ->
            val cell = workbook.getSheet("Data").getRow(1).getCell(0)
            assertThat(cell.cellType).isEqualTo(CellType.STRING)
            assertThat(cell.stringCellValue).isEqualTo("=1+1")
            // POI itself refuses to read a formula off a non-formula cell — the strongest available
            // proof that setCellFormula was never called for this value.
            assertThatThrownBy { cell.cellFormula }.isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test
    fun `every other formula-injection trigger character also writes as a plain string cell`() {
        val triggerValues = listOf("+1", "-1", "@SUM(A1)", "\t=x", "\r=x")
        val bytes = writeWorkbook { writer ->
            val sheet = writer.sheet("Data")
            sheet.header(listOf("Col"), listOf(8))
            triggerValues.forEach { value -> sheet.row().text(value) }
        }
        readWorkbook(bytes).use { workbook ->
            val sheet = workbook.getSheet("Data")
            triggerValues.forEachIndexed { index, _ ->
                val cell = sheet.getRow(index + 1).getCell(0)
                assertThat(cell.cellType).describedAs("row $index").isEqualTo(CellType.STRING)
            }
        }
    }
}
