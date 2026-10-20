package de.littletrotter.infrastructure.export.workbook.trip

import de.littletrotter.application.export.TripWorkbookView
import de.littletrotter.infrastructure.export.workbook.readWorkbook
import de.littletrotter.infrastructure.export.workbook.writeWorkbook
import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import de.littletrotter.shared.TripExpenseExportDto
import org.apache.poi.ss.usermodel.CellType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** direct unit coverage for [TripExpenseSheetWriter], complementing the full-workbook
 *  round trip already exercised by [TripWorkbookExportServiceTest]. */
class TripExpenseSheetWriterTest {

    private val columnHeaders = (0 until TripExpenseSheetWriter.COLUMN_COUNT).map { "Col$it" }

    private val labels = TripExpenseSheetLabels(
        columnHeaders = columnHeaders,
        kindLabels = mapOf("trip.kind.hotel" to "Unterkunft", "trip.kind.mileage" to "Kilometer"),
        totalRowLabel = "Summe",
    )

    private fun view(expenses: List<TripExpenseExportDto>, positionId: String = "3") = TripWorkbookView(
        positionId = positionId,
        title = "Workshop Hamburg",
        purpose = null,
        periodFrom = null,
        periodTo = null,
        invoiceNumber = "RE-2026-0001",
        invoiceDate = null,
        customerName = null,
        costCenter = null,
        taxRatePercent = null,
        calculatedNet = Money.ZERO,
        fixedNetPrice = null,
        receiptsCount = 0,
        currency = Currency.EUR,
        expenses = expenses,
        perDiemDays = emptyList(),
    )

    private fun expense(
        kindLabelKey: String = "trip.kind.hotel",
        description: String = "Hotel Stern",
        quantity: String = "1",
        unitPriceNet: String = "120.00",
    ) = TripExpenseExportDto(
        kindLabelKey = kindLabelKey,
        description = description,
        travelDate = "2026-02-08",
        quantity = BigDecimal(quantity),
        unitLabel = "Stk",
        unitPriceNet = Money.of(unitPriceNet),
        netAmount = Money.of(unitPriceNet),
    )

    @Test
    fun `writes the header row and one row per expense, the net cell as a real formula, the total row as a SUM`() {
        val bytes = writeWorkbook { writer -> TripExpenseSheetWriter.write(writer, labels, view(listOf(expense(), expense(kindLabelKey = "trip.kind.mileage", description = "120 km", quantity = "120", unitPriceNet = "0.30")))) }

        readWorkbook(bytes).use { wb ->
            val sheet = wb.getSheet(TripExpenseSheetWriter.SHEET_NAME)
            assertThat(sheet.getRow(0).getCell(0).stringCellValue).isEqualTo("Col0")

            val firstRow = sheet.getRow(1)
            assertThat(firstRow.getCell(0).stringCellValue).isEqualTo("3.1")
            assertThat(firstRow.getCell(1).stringCellValue).isEqualTo("Unterkunft")
            assertThat(firstRow.getCell(2).stringCellValue).isEqualTo("Hotel Stern")
            assertThat(firstRow.getCell(3).localDateTimeCellValue.toLocalDate()).isEqualTo(LocalDate.of(2026, 2, 8))
            assertThat(firstRow.getCell(6).numericCellValue).isEqualTo(120.00)
            assertThat(firstRow.getCell(7).cellFormula).isEqualTo("E2*G2")

            val secondRow = sheet.getRow(2)
            assertThat(secondRow.getCell(1).stringCellValue).isEqualTo("Kilometer")
            assertThat(secondRow.getCell(0).stringCellValue).isEqualTo("3.2")
            assertThat(secondRow.getCell(7).cellFormula).isEqualTo("E3*G3")

            val totalRow = sheet.getRow(3)
            assertThat(totalRow.getCell(0).stringCellValue).isEqualTo("Summe")
            assertThat(totalRow.getCell(7).cellFormula).isEqualTo("SUM(H2:H3)")

            val evaluator = wb.creationHelper.createFormulaEvaluator()
            assertThat(evaluator.evaluate(totalRow.getCell(7)).numberValue).isEqualTo(156.00)
        }
    }

    @Test
    fun `an expense row's trailing per-diem columns and control block are always blank`() {
        val bytes = writeWorkbook { writer -> TripExpenseSheetWriter.write(writer, labels, view(listOf(expense()))) }
        readWorkbook(bytes).use { wb ->
            val dataRow = wb.getSheet(TripExpenseSheetWriter.SHEET_NAME).getRow(1)
            (8..13).forEach { column -> assertThat(dataRow.getCell(column).cellType).describedAs("column $column").isEqualTo(CellType.BLANK) }
        }
    }

    @Test
    fun `an empty expense list writes only the total row, whose net cell is a plain zero, never a SUM over an empty range`() {
        val bytes = writeWorkbook { writer -> TripExpenseSheetWriter.write(writer, labels, view(emptyList())) }
        readWorkbook(bytes).use { wb ->
            val sheet = wb.getSheet(TripExpenseSheetWriter.SHEET_NAME)
            assertThat(sheet.physicalNumberOfRows).isEqualTo(2) // header + total row only
            val totalRow = sheet.getRow(1)
            assertThat(totalRow.getCell(7).cellType).isEqualTo(CellType.NUMERIC)
            assertThat(totalRow.getCell(7).numericCellValue).isEqualTo(0.0)
        }
    }

    @Test
    fun `an expense kind not present in the label map falls back to its own raw key`() {
        val bytes = writeWorkbook { writer -> TripExpenseSheetWriter.write(writer, labels, view(listOf(expense(kindLabelKey = "trip.kind.other")))) }
        readWorkbook(bytes).use { wb ->
            val dataRow = wb.getSheet(TripExpenseSheetWriter.SHEET_NAME).getRow(1)
            assertThat(dataRow.getCell(1).stringCellValue).isEqualTo("trip.kind.other")
        }
    }

    @Test
    fun `rejects a label set whose column headers do not match the sheet's own column count`() {
        val badLabels = labels.copy(columnHeaders = listOf("only one"))
        assertThatThrownBy { writeWorkbook { writer -> TripExpenseSheetWriter.write(writer, badLabels, view(emptyList())) } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("TripExpenseSheetLabels.columnHeaders must have")
    }

    @Test
    fun `COLUMN_WIDTHS has exactly one entry per column`() {
        assertThat(TripExpenseSheetWriter.COLUMN_WIDTHS).hasSize(TripExpenseSheetWriter.COLUMN_COUNT)
    }
}
