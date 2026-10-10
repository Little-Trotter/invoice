package de.littletrotter.infrastructure.export.workbook

import de.littletrotter.application.export.InvoiceHeaderExportRow
import de.littletrotter.application.export.PartySource
import de.littletrotter.domain.invoice.DocumentEngine
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import org.apache.poi.ss.usermodel.CellType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

class InvoiceHeaderSheetWriterTest {

    private val columnHeaders = (0 until InvoiceHeaderSheetWriter.COLUMN_COUNT).map { "Col$it" }

    private val labels = InvoiceHeaderSheetLabels(
        columnHeaders = columnHeaders,
        enumLabels = InvoiceHeaderEnumLabels(
            typeCode = mapOf(
                InvoiceTypeCode.COMMERCIAL_INVOICE to "Commercial invoice",
                InvoiceTypeCode.CORRECTED_INVOICE to "Corrected invoice",
            ),
            status = mapOf(InvoiceStatus.DRAFT to "Draft", InvoiceStatus.SENT to "Sent"),
            validationStatus = mapOf(ValidationStatus.NOT_VALIDATED to "Not validated", ValidationStatus.VALID to "Valid"),
            invoiceFormat = mapOf(InvoiceFormat.GERMAN to "German", InvoiceFormat.INTERNATIONAL to "International"),
            documentEngine = mapOf(DocumentEngine.MUSTANG to "Mustang", DocumentEngine.FACTURX to "Factur-X"),
            partySource = mapOf(PartySource.SNAPSHOT to "Snapshot", PartySource.MASTER_DATA to "Master data"),
        ),
        verdictOkLabel = "OK",
        verdictDeviationLabel = "Deviation",
        malformedNumberMarkerPattern = "{0} (invalid format)",
    )

    private fun baseRow(invoiceId: Long = 1L, invoiceDate: LocalDate = LocalDate.of(2026, 1, 15)) =
        InvoiceHeaderExportRow(invoiceId = invoiceId, invoiceDate = invoiceDate)

    @Test
    fun `writes the header row from the supplied labels and creates exactly one row per invoice`() {
        val bytes = writeWorkbook { writer ->
            InvoiceHeaderSheetWriter.write(writer, labels, listOf(baseRow(), baseRow(invoiceId = 2L)), positionSheetLastDataRow = 1)
        }
        readWorkbook(bytes).use { wb ->
            val sheet = wb.getSheet(InvoiceHeaderSheetWriter.SHEET_NAME)
            assertThat(sheet).isNotNull
            assertThat(sheet.getRow(0).getCell(0).stringCellValue).isEqualTo("Col0")
            assertThat(sheet.getRow(0).getCell(69).stringCellValue).isEqualTo("Col69")
            assertThat(sheet.lastRowNum).isEqualTo(2)
        }
    }

    @Test
    fun `rejects a label set whose column headers do not match the sheet's column count`() {
        val badLabels = labels.copy(columnHeaders = listOf("only one"))
        assertThatThrownBy { writeWorkbook { writer -> InvoiceHeaderSheetWriter.write(writer, badLabels, emptyList(), 1) } }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `writes every typed cell of a fully populated row with the right CellType, value and format`() {
        val row = InvoiceHeaderExportRow(
            invoiceId = 7L,
            invoiceNumber = "IP-2026-03-00042",
            invoiceNumberPrefix = "IP-",
            invoiceNumberSequence = 42L,
            typeCode = InvoiceTypeCode.CORRECTED_INVOICE,
            status = InvoiceStatus.SENT,
            validationStatus = ValidationStatus.VALID,
            invoiceFormat = InvoiceFormat.INTERNATIONAL,
            documentLanguage = "en",
            documentEngine = DocumentEngine.FACTURX,
            invoiceDate = LocalDate.of(2026, 3, 1),
            paymentTermsDays = 30,
            paymentTermSnapshotName = "Net 30",
            paymentTermSnapshotDiscountPercent = BigDecimal("2.50"),
            paymentTermSnapshotDiscountDays = 7,
            sellerName = "Seller GmbH",
            partySource = PartySource.SNAPSHOT,
            currency = Currency.CHF,
            reverseCharge = true,
            netAmount = Money(BigDecimal("100.00")),
            vatAmount = Money(BigDecimal("19.00")),
            grossAmount = Money(BigDecimal("119.00")),
            itemsCount = 3,
            receiptsCount = 1,
            needsRecalculation = true,
            createdAt = OffsetDateTime.parse("2026-03-01T09:00:00Z"),
            version = 4L,
            freeText = "Bitte pruefen",
        )

        val bytes = writeWorkbook { writer -> InvoiceHeaderSheetWriter.write(writer, labels, listOf(row), positionSheetLastDataRow = 5) }

        readWorkbook(bytes).use { wb ->
            val dataRow = wb.getSheet(InvoiceHeaderSheetWriter.SHEET_NAME).getRow(1)

            assertThat(dataRow.getCell(0).cellType).isEqualTo(CellType.NUMERIC)
            assertThat(dataRow.getCell(0).numericCellValue).isEqualTo(7.0)
            assertThat(dataRow.getCell(1).stringCellValue).isEqualTo("IP-2026-03-00042")
            assertThat(dataRow.getCell(4).stringCellValue).isEqualTo("384")
            assertThat(dataRow.getCell(5).stringCellValue).isEqualTo("Corrected invoice")
            assertThat(dataRow.getCell(6).stringCellValue).isEqualTo("Sent")
            assertThat(dataRow.getCell(7).stringCellValue).isEqualTo("Valid")
            assertThat(dataRow.getCell(8).stringCellValue).isEqualTo("International")
            assertThat(dataRow.getCell(10).stringCellValue).isEqualTo("Factur-X")
            assertThat(dataRow.getCell(11).cellType).isEqualTo(CellType.NUMERIC)
            assertThat(dataRow.getCell(11).localDateTimeCellValue.toLocalDate()).isEqualTo(LocalDate.of(2026, 3, 1))
            assertThat(dataRow.getCell(11).cellStyle.dataFormatString).isEqualTo(CellStyles.DATE_FORMAT)
            assertThat(dataRow.getCell(18).numericCellValue).isEqualTo(2.50)
            assertThat(dataRow.getCell(18).cellStyle.dataFormatString).isEqualTo(CellStyles.PERCENT_FORMAT)
            assertThat(dataRow.getCell(20).stringCellValue).isEqualTo("Seller GmbH")
            assertThat(dataRow.getCell(36).stringCellValue).isEqualTo("Snapshot")
            assertThat(dataRow.getCell(47).stringCellValue).isEqualTo("CHF")
            assertThat(dataRow.getCell(48).booleanCellValue).isTrue()
            assertThat(dataRow.getCell(49).numericCellValue).isEqualTo(100.00)
            // R7 — the cell's own number format names the row's currency (CHF here), so a reader
            // sees the same "100,00 CHF" the UI shows, never a bare number that could be mistaken
            // for Euro.
            assertThat(dataRow.getCell(49).cellStyle.dataFormatString).isEqualTo("#,##0.00 \"CHF\"")
            assertThat(dataRow.getCell(52).numericCellValue).isEqualTo(3.0)
            assertThat(dataRow.getCell(54).booleanCellValue).isTrue()
            assertThat(dataRow.getCell(55).cellType).isEqualTo(CellType.NUMERIC)
            assertThat(dataRow.getCell(59).numericCellValue).isEqualTo(4.0)
            assertThat(dataRow.getCell(60).stringCellValue).isEqualTo("Bitte pruefen")
        }
    }

    @Test
    fun `a blank draft invoice number and every unset optional column write as genuinely blank cells`() {
        val bytes = writeWorkbook { writer -> InvoiceHeaderSheetWriter.write(writer, labels, listOf(baseRow()), positionSheetLastDataRow = 1) }
        readWorkbook(bytes).use { wb ->
            val dataRow = wb.getSheet(InvoiceHeaderSheetWriter.SHEET_NAME).getRow(1)
            assertThat(dataRow.getCell(1).cellType).isEqualTo(CellType.BLANK)
            assertThat(dataRow.getCell(2).cellType).isEqualTo(CellType.BLANK)
            assertThat(dataRow.getCell(12).cellType).isEqualTo(CellType.BLANK)
        }
    }

    @Test
    fun `a malformed stored invoice number is exported with the raw text plus the marker`() {
        val row = baseRow().copy(invoiceNumber = "GARBAGE", invoiceNumberMalformed = true)
        val bytes = writeWorkbook { writer -> InvoiceHeaderSheetWriter.write(writer, labels, listOf(row), positionSheetLastDataRow = 1) }
        readWorkbook(bytes).use { wb ->
            val dataRow = wb.getSheet(InvoiceHeaderSheetWriter.SHEET_NAME).getRow(1)
            assertThat(dataRow.getCell(1).stringCellValue).isEqualTo("GARBAGE (invalid format)")
        }
    }

    @Test
    fun `an enum value missing from the supplied label map falls back to its enum name rather than failing`() {
        val row = baseRow().copy(status = InvoiceStatus.VOIDED) // VOIDED not in the test's status label map
        val bytes = writeWorkbook { writer -> InvoiceHeaderSheetWriter.write(writer, labels, listOf(row), positionSheetLastDataRow = 1) }
        readWorkbook(bytes).use { wb ->
            val dataRow = wb.getSheet(InvoiceHeaderSheetWriter.SHEET_NAME).getRow(1)
            assertThat(dataRow.getCell(6).stringCellValue).isEqualTo("VOIDED")
        }
    }

    @Test
    fun `the control-formula block computes exact SUMIF, COUNTIF, delta and verdict text against the given position-sheet row count`() {
        val bytes = writeWorkbook { writer ->
            InvoiceHeaderSheetWriter.write(writer, labels, listOf(baseRow(invoiceId = 3L), baseRow(invoiceId = 9L)), positionSheetLastDataRow = 501)
        }
        readWorkbook(bytes).use { wb ->
            val sheet = wb.getSheet(InvoiceHeaderSheetWriter.SHEET_NAME)
            val firstDataRow = sheet.getRow(1) // Excel row 2
            val secondDataRow = sheet.getRow(2) // Excel row 3

            assertThat(firstDataRow.getCell(61).cellType).isEqualTo(CellType.FORMULA)
            assertThat(firstDataRow.getCell(61).cellFormula)
                .isEqualTo("SUMIFS(Positionen!\$V\$2:\$V\$501,Positionen!\$A\$2:\$A\$501,\$A2,Positionen!\$AE\$2:\$AE\$501,0)")
            assertThat(firstDataRow.getCell(61).cellStyle.dataFormatString).isEqualTo(CellStyles.MONEY_FORMAT)

            assertThat(firstDataRow.getCell(62).cellFormula).isEqualTo("BJ2-AX2")
            assertThat(firstDataRow.getCell(63).cellFormula)
                .isEqualTo("SUMIFS(Positionen!\$W\$2:\$W\$501,Positionen!\$A\$2:\$A\$501,\$A2,Positionen!\$AE\$2:\$AE\$501,0)")
            assertThat(firstDataRow.getCell(64).cellFormula).isEqualTo("BL2-AY2")
            assertThat(firstDataRow.getCell(65).cellFormula).isEqualTo("BJ2+BL2")
            assertThat(firstDataRow.getCell(66).cellFormula).isEqualTo("BN2-AZ2")
            assertThat(firstDataRow.getCell(67).cellFormula)
                .isEqualTo("COUNTIFS(Positionen!\$A\$2:\$A\$501,\$A2,Positionen!\$AE\$2:\$AE\$501,0)")
            assertThat(firstDataRow.getCell(67).cellStyle.dataFormatString).isEqualTo(CellStyles.INTEGER_FORMAT)
            assertThat(firstDataRow.getCell(68).cellFormula).isEqualTo("BP2-BA2")
            assertThat(firstDataRow.getCell(69).cellFormula)
                .isEqualTo("IF(MAX(ABS(BK2),ABS(BM2),ABS(BO2),ABS(BQ2))>0.005,\"Deviation\",\"OK\")")

            // Second row: only the row number moves, column letters and range stay identical.
            assertThat(secondDataRow.getCell(61).cellFormula)
                .isEqualTo("SUMIFS(Positionen!\$V\$2:\$V\$501,Positionen!\$A\$2:\$A\$501,\$A3,Positionen!\$AE\$2:\$AE\$501,0)")
            assertThat(secondDataRow.getCell(62).cellFormula).isEqualTo("BJ3-AX3")
        }
    }

    @Test
    fun `a custom position-sheet column layout is reflected verbatim in the SUMIFS ranges`() {
        val customColumns = PositionSheetReferenceColumns(invoiceIdColumn = "B", netAmountColumn = "S", vatAmountColumn = "T", levelColumn = "U")
        val bytes = writeWorkbook { writer ->
            InvoiceHeaderSheetWriter.write(writer, labels, listOf(baseRow()), positionSheetLastDataRow = 10, positionColumns = customColumns)
        }
        readWorkbook(bytes).use { wb ->
            val dataRow = wb.getSheet(InvoiceHeaderSheetWriter.SHEET_NAME).getRow(1)
            assertThat(dataRow.getCell(61).cellFormula)
                .isEqualTo("SUMIFS(Positionen!\$S\$2:\$S\$10,Positionen!\$B\$2:\$B\$10,\$A2,Positionen!\$U\$2:\$U\$10,0)")
            assertThat(dataRow.getCell(63).cellFormula)
                .isEqualTo("SUMIFS(Positionen!\$T\$2:\$T\$10,Positionen!\$B\$2:\$B\$10,\$A2,Positionen!\$U\$2:\$U\$10,0)")
        }
    }

    @Test
    fun `the Delta columns carry a conditional-formatting rule instead of a static highlight`() {
        val bytes = writeWorkbook { writer ->
            InvoiceHeaderSheetWriter.write(writer, labels, listOf(baseRow(), baseRow(invoiceId = 2L)), positionSheetLastDataRow = 1)
        }
        readWorkbook(bytes).use { wb ->
            val sheet = wb.getSheet(InvoiceHeaderSheetWriter.SHEET_NAME)
            // Four Δ columns (Netto/Steuer/Brutto/Positionen) — see DeviationConditionalFormattingTest
            // for the rule's own fill/font/formula assertions; this only checks actually wires
            // the shared rule in — required by the export spec.
            assertThat(sheet.sheetConditionalFormatting.numConditionalFormattings).isEqualTo(4)
        }
    }

    // ── column widths declared next to the column catalogue ───────────────────────────

    @Test
    fun `COLUMN_WIDTHS has exactly one entry per column`() {
        assertThat(InvoiceHeaderSheetWriter.COLUMN_WIDTHS).hasSize(InvoiceHeaderSheetWriter.COLUMN_COUNT)
    }

    @Test
    fun `every money and Δ-money column is width-classed MONEY, every date column DATE`() {
        val widths = InvoiceHeaderSheetWriter.COLUMN_WIDTHS
        val moneyColumns = listOf(49, 50, 51, 61, 62, 63, 64, 65, 66) // net/vat/gross + control/Δ block
        moneyColumns.forEach { column -> assertThat(widths[column]).describedAs("column $column").isEqualTo(ColumnWidths.MONEY) }
        val dateColumns = listOf(11, 12, 13, 14, 15, 40, 42, 46) // invoiceDate..dueDate, order/offer refs, storno date
        dateColumns.forEach { column -> assertThat(widths[column]).describedAs("column $column").isEqualTo(ColumnWidths.DATE) }
    }

    @Test
    fun `the free-text column - freeText, index 60 - is width-classed TEXT and wraps`() {
        assertThat(InvoiceHeaderSheetWriter.COLUMN_WIDTHS[60]).isEqualTo(ColumnWidths.TEXT)
        val bytes = writeWorkbook { writer ->
            InvoiceHeaderSheetWriter.write(writer, labels, listOf(baseRow().copy(freeText = "a long free text note")), positionSheetLastDataRow = 1)
        }
        readWorkbook(bytes).use { wb ->
            val cell = wb.getSheet(InvoiceHeaderSheetWriter.SHEET_NAME).getRow(1).getCell(60)
            assertThat(cell.cellStyle.wrapText).isTrue()
        }
    }
}
