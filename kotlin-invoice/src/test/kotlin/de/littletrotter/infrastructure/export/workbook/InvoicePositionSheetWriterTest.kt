package de.littletrotter.infrastructure.export.workbook

import de.littletrotter.application.export.InvoicePositionExportRow
import de.littletrotter.application.export.PerDiemDetailColumns
import de.littletrotter.domain.invoice.TripExpenseKind
import de.littletrotter.shared.tax.VatCategory
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.Money
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class InvoicePositionSheetWriterTest {

    private val columnHeaders = (0 until InvoicePositionSheetWriter.COLUMN_COUNT).map { "Col$it" }

    private val labels = InvoicePositionSheetLabels(
        columnHeaders = columnHeaders,
        enumLabels = InvoicePositionEnumLabels(
            category = mapOf(
                InvoiceItemCategory.SERVICE to "Dienstleistung",
                InvoiceItemCategory.PRODUCT to "Produkt",
                InvoiceItemCategory.TRIP to "Dienstreise",
            ),
            vatCategory = mapOf(
                VatCategory.STANDARD to "Regelsteuersatz",
                VatCategory.REVERSE_CHARGE to "Reverse Charge",
                VatCategory.EXEMPT to "Steuerbefreit",
                VatCategory.ZERO_RATED to "Nullsatz",
            ),
            tripExpenseKind = mapOf(
                TripExpenseKind.HOTEL to "Unterkunft",
                TripExpenseKind.TRANSPORT to "Fahrkarten",
                TripExpenseKind.MILEAGE to "Kilometer",
                TripExpenseKind.OTHER to "Sonstiges",
            ),
        ),
        verdictOkLabel = "OK",
        verdictDeviationLabel = "Abweichung",
        verdictTaxRateMissingLabel = "Steuersatz fehlt",
        perDiemKindLabel = "Verpflegungspauschale",
    )

    private fun baseRow(invoiceId: Long = 1L, positionId: String = "1") = InvoicePositionExportRow(invoiceId = invoiceId, positionId = positionId)

    @Test
    fun `writes the header row from the supplied labels and creates exactly one row per position`() {
        val bytes = writeWorkbook { writer -> InvoicePositionSheetWriter.write(writer, labels, listOf(baseRow(), baseRow(positionId = "2"))) }
        readWorkbook(bytes).use { wb ->
            val sheet = wb.getSheet(InvoicePositionSheetWriter.SHEET_NAME)
            assertThat(sheet).isNotNull
            assertThat(sheet.getRow(0).getCell(0).stringCellValue).isEqualTo("Col0")
            assertThat(sheet.getRow(0).getCell(46).stringCellValue).isEqualTo("Col46")
            assertThat(sheet.lastRowNum).isEqualTo(2)
        }
    }

    @Test
    fun `rejects a label set whose column headers do not match the sheet's column count`() {
        val badLabels = labels.copy(columnHeaders = listOf("only one"))
        assertThatThrownBy { writeWorkbook { writer -> InvoicePositionSheetWriter.write(writer, badLabels, emptyList()) } }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `writes every typed cell of a fully populated row with the right CellType, value and format`() {
        val row = InvoicePositionExportRow(
            invoiceId = 7L,
            invoiceNumber = "IP-2026-03-00042",
            positionId = "3",
            sortOrder = 2,
            category = InvoiceItemCategory.PRODUCT,
            description = "Ersatzteil",
            itemDescription = "Ersatzteil, 40 Stueck",
            sellerItemIdentifier = "SELLER-1",
            buyerItemIdentifier = "BUYER-1",
            articleNumber = "ART-9",
            quantity = BigDecimal("40.0000"),
            unit = InvoiceItemUnit.PIECE,
            unitPriceNet = Money(BigDecimal("12.50")),
            taxCategoryCode = "S",
            taxRatePercent = BigDecimal("19.00"),
            taxRuleCode = "TR-STD",
            netAmount = Money(BigDecimal("500.00")),
            vatAmount = Money(BigDecimal("95.00")),
            grossAmount = Money(BigDecimal("595.00")),
            servicePeriodFrom = LocalDate.of(2026, 3, 1),
            servicePeriodTo = LocalDate.of(2026, 3, 31),
            costCenter = "CC-1",
            deliveryNoteReference = "LS-2026-042",
            receiptsCount = 2,
            receiptFileNames = "beleg1.pdf, beleg2.pdf",
        )

        val bytes = writeWorkbook { writer -> InvoicePositionSheetWriter.write(writer, labels, listOf(row)) }

        readWorkbook(bytes).use { wb ->
            val dataRow = wb.getSheet(InvoicePositionSheetWriter.SHEET_NAME).getRow(1)

            assertThat(dataRow.getCell(0).cellType).isEqualTo(CellType.NUMERIC)
            assertThat(dataRow.getCell(0).numericCellValue).isEqualTo(7.0)
            assertThat(dataRow.getCell(1).stringCellValue).isEqualTo("IP-2026-03-00042")
            assertThat(dataRow.getCell(2).stringCellValue).isEqualTo("3")
            assertThat(dataRow.getCell(3).numericCellValue).isEqualTo(2.0)
            assertThat(dataRow.getCell(4).stringCellValue).isEqualTo("Produkt")
            assertThat(dataRow.getCell(5).stringCellValue).isEqualTo("Ersatzteil")
            assertThat(dataRow.getCell(6).stringCellValue).isEqualTo("Ersatzteil, 40 Stueck")
            assertThat(dataRow.getCell(7).stringCellValue).isEqualTo("SELLER-1")
            assertThat(dataRow.getCell(8).stringCellValue).isEqualTo("BUYER-1")
            assertThat(dataRow.getCell(9).stringCellValue).isEqualTo("ART-9")
            assertThat(dataRow.getCell(10).numericCellValue).isEqualTo(40.0)
            assertThat(dataRow.getCell(10).cellStyle.dataFormatString).isEqualTo(CellStyles.QUANTITY_FORMAT)
            assertThat(dataRow.getCell(11).stringCellValue).isEqualTo("Stk")
            assertThat(dataRow.getCell(12).stringCellValue).isEqualTo("H87")
            assertThat(dataRow.getCell(13).numericCellValue).isEqualTo(12.50)
            // R7 — the cell's own number format names the row's currency (EUR, the fixture's
            // default), so a reader sees "12,50 EUR", not a bare number.
            assertThat(dataRow.getCell(13).cellStyle.dataFormatString).isEqualTo("#,##0.00 \"EUR\"")
            assertThat(dataRow.getCell(14).stringCellValue).isEqualTo("S")
            assertThat(dataRow.getCell(15).stringCellValue).isEqualTo("Regelsteuersatz")
            assertThat(dataRow.getCell(16).numericCellValue).isEqualTo(19.0)
            assertThat(dataRow.getCell(16).cellStyle.dataFormatString).isEqualTo(CellStyles.PERCENT_FORMAT)
            assertThat(dataRow.getCell(17).stringCellValue).isEqualTo("TR-STD")
            assertThat(dataRow.getCell(21).numericCellValue).isEqualTo(500.00)
            assertThat(dataRow.getCell(22).numericCellValue).isEqualTo(95.00)
            assertThat(dataRow.getCell(23).numericCellValue).isEqualTo(595.00)
            assertThat(dataRow.getCell(24).localDateTimeCellValue.toLocalDate()).isEqualTo(LocalDate.of(2026, 3, 1))
            assertThat(dataRow.getCell(25).localDateTimeCellValue.toLocalDate()).isEqualTo(LocalDate.of(2026, 3, 31))
            assertThat(dataRow.getCell(26).stringCellValue).isEqualTo("CC-1")
            assertThat(dataRow.getCell(27).stringCellValue).isEqualTo("LS-2026-042")
            assertThat(dataRow.getCell(28).numericCellValue).isEqualTo(2.0)
            assertThat(dataRow.getCell(29).stringCellValue).isEqualTo("beleg1.pdf, beleg2.pdf")
        }
    }

    @Test
    fun `a blank draft invoice number and every unset optional column write as genuinely blank cells`() {
        val bytes = writeWorkbook { writer -> InvoicePositionSheetWriter.write(writer, labels, listOf(baseRow())) }
        readWorkbook(bytes).use { wb ->
            val dataRow = wb.getSheet(InvoicePositionSheetWriter.SHEET_NAME).getRow(1)
            assertThat(dataRow.getCell(1).cellType).isEqualTo(CellType.BLANK) // invoiceNumber blank
            assertThat(dataRow.getCell(6).cellType).isEqualTo(CellType.BLANK) // itemDescription, no fallback
            assertThat(dataRow.getCell(7).cellType).isEqualTo(CellType.BLANK) // no BT-155
            assertThat(dataRow.getCell(8).cellType).isEqualTo(CellType.BLANK) // no BT-156
            assertThat(dataRow.getCell(9).cellType).isEqualTo(CellType.BLANK) // no articleRef
            assertThat(dataRow.getCell(15).cellType).isEqualTo(CellType.BLANK) // no tax category -> no label
            assertThat(dataRow.getCell(16).cellType).isEqualTo(CellType.BLANK) // missing tax rate
            assertThat(dataRow.getCell(29).cellType).isEqualTo(CellType.BLANK) // no receipts
        }
    }

    @Test
    fun `an unrecognised or absent tax category code writes a blank label, never a fallback name`() {
        val row = baseRow().copy(taxCategoryCode = "ZZZ-UNKNOWN")
        val bytes = writeWorkbook { writer -> InvoicePositionSheetWriter.write(writer, labels, listOf(row)) }
        readWorkbook(bytes).use { wb ->
            val dataRow = wb.getSheet(InvoicePositionSheetWriter.SHEET_NAME).getRow(1)
            assertThat(dataRow.getCell(14).stringCellValue).isEqualTo("ZZZ-UNKNOWN")
            assertThat(dataRow.getCell(15).cellType).isEqualTo(CellType.BLANK)
        }
    }

    @Test
    fun `a discount line - negative unit price - is written as a genuine negative money cell`() {
        val row = baseRow().copy(unitPriceNet = Money(BigDecimal("-10.00")), netAmount = Money(BigDecimal("-10.00")))
        val bytes = writeWorkbook { writer -> InvoicePositionSheetWriter.write(writer, labels, listOf(row)) }
        readWorkbook(bytes).use { wb ->
            val dataRow = wb.getSheet(InvoicePositionSheetWriter.SHEET_NAME).getRow(1)
            assertThat(dataRow.getCell(13).numericCellValue).isEqualTo(-10.00)
            assertThat(dataRow.getCell(21).numericCellValue).isEqualTo(-10.00)
        }
    }

    @Test
    fun `two invoices whose positions share a positionId stay distinguishable by their own invoiceId column`() {
        val bytes = writeWorkbook { writer ->
            InvoicePositionSheetWriter.write(writer, labels, listOf(baseRow(invoiceId = 1L, positionId = "1"), baseRow(invoiceId = 2L, positionId = "1")))
        }
        readWorkbook(bytes).use { wb ->
            val sheet = wb.getSheet(InvoicePositionSheetWriter.SHEET_NAME)
            assertThat(sheet.getRow(1).getCell(0).numericCellValue).isEqualTo(1.0)
            assertThat(sheet.getRow(1).getCell(2).stringCellValue).isEqualTo("1")
            assertThat(sheet.getRow(2).getCell(0).numericCellValue).isEqualTo(2.0)
            assertThat(sheet.getRow(2).getCell(2).stringCellValue).isEqualTo("1")
        }
    }

    @Test
    fun `a description far beyond the OOXML cell text limit is truncated and the workbook still opens`() {
        val hugeDescription = "x".repeat(40_000)
        val row = baseRow().copy(description = hugeDescription)
        val bytes = writeWorkbook { writer -> InvoicePositionSheetWriter.write(writer, labels, listOf(row)) }
        readWorkbook(bytes).use { wb ->
            val value = wb.getSheet(InvoicePositionSheetWriter.SHEET_NAME).getRow(1).getCell(5).stringCellValue
            assertThat(value.length).isLessThan(hugeDescription.length)
            assertThat(value.length).isLessThanOrEqualTo(TextGuard.MAX_LENGTH)
        }
    }

    @Test
    fun `the control-formula block writes the exact canonical ROUND, delta and verdict formulas`() {
        val bytes = writeWorkbook { writer -> InvoicePositionSheetWriter.write(writer, labels, listOf(baseRow(), baseRow(positionId = "2"))) }
        readWorkbook(bytes).use { wb ->
            val sheet = wb.getSheet(InvoicePositionSheetWriter.SHEET_NAME)
            val firstDataRow = sheet.getRow(1) // Excel row 2
            val secondDataRow = sheet.getRow(2) // Excel row 3

            assertThat(firstDataRow.getCell(40).cellType).isEqualTo(CellType.FORMULA)
            assertThat(firstDataRow.getCell(40).cellFormula).isEqualTo("ROUND(K2*N2,2)")
            assertThat(firstDataRow.getCell(40).cellStyle.dataFormatString).isEqualTo(CellStyles.MONEY_FORMAT)
            assertThat(firstDataRow.getCell(41).cellFormula).isEqualTo("AO2-V2")
            assertThat(firstDataRow.getCell(42).cellFormula).isEqualTo("ROUND(AO2*Q2/100,2)")
            assertThat(firstDataRow.getCell(43).cellFormula).isEqualTo("AQ2-W2")
            assertThat(firstDataRow.getCell(44).cellFormula).isEqualTo("AO2+AQ2")
            assertThat(firstDataRow.getCell(45).cellFormula).isEqualTo("AS2-X2")
            assertThat(firstDataRow.getCell(46).cellFormula)
                .isEqualTo("IF(ISBLANK(Q2),\"Steuersatz fehlt\",IF(MAX(ABS(AP2),ABS(AR2),ABS(AT2))>0.005,\"Abweichung\",\"OK\"))")

            // Second row: only the row number moves, column letters stay identical.
            assertThat(secondDataRow.getCell(40).cellFormula).isEqualTo("ROUND(K3*N3,2)")
            assertThat(secondDataRow.getCell(46).cellFormula)
                .isEqualTo("IF(ISBLANK(Q3),\"Steuersatz fehlt\",IF(MAX(ABS(AP3),ABS(AR3),ABS(AT3))>0.005,\"Abweichung\",\"OK\"))")
        }
    }

    @Test
    fun `the Delta columns carry a conditional-formatting rule instead of a static highlight`() {
        val bytes = writeWorkbook { writer -> InvoicePositionSheetWriter.write(writer, labels, listOf(baseRow(), baseRow(positionId = "2"))) }
        readWorkbook(bytes).use { wb ->
            val sheet = wb.getSheet(InvoicePositionSheetWriter.SHEET_NAME)
            // Δ Netto / Δ Steuer / Δ Brutto — see DeviationConditionalFormattingTest for the rule's
            // own fill/font/formula assertions.
            assertThat(sheet.sheetConditionalFormatting.numConditionalFormattings).isEqualTo(3)
        }
    }

    // ── Formula evaluation — the export checking itself, required by definition of done ──────────

    @Test
    fun `POI's FormulaEvaluator computes every control formula equal to the stored, correctly-rounded amounts`() {
        val rows = listOf(
            // quantity 0 with a non-zero price -> net 0, delta 0.
            baseRow(positionId = "zero-qty").copy(quantity = BigDecimal.ZERO, unitPriceNet = Money(BigDecimal("50.00")), taxRatePercent = BigDecimal("19"), netAmount = Money.ZERO, vatAmount = Money.ZERO, grossAmount = Money.ZERO),
            // a rounding case only HALF_UP gets right: 3 x 33.33 = 99.99, then 99.99 x 19 / 100 =
            // 18.9981, which HALF_UP rounds up to 19.00 (not down to 18.99).
            baseRow(positionId = "half-up-exact").copy(
                quantity = BigDecimal("3"), unitPriceNet = Money(BigDecimal("33.33")), taxRatePercent = BigDecimal("19"),
                netAmount = Money(BigDecimal("99.99")), vatAmount = Money(BigDecimal("19.00")), grossAmount = Money(BigDecimal("118.99")),
            ),
            // a discount line: negative unit price, HALF_UP away from zero even for a -0.125 half-cent case.
            baseRow(positionId = "discount-half-up").copy(
                quantity = BigDecimal.ONE, unitPriceNet = Money(BigDecimal("-0.125")), taxRatePercent = BigDecimal("19"),
                netAmount = Money(BigDecimal("-0.13")), vatAmount = Money(BigDecimal("-0.02")), grossAmount = Money(BigDecimal("-0.15")),
            ),
            // reverse-charge: category AE, rate 0, tax amount 0.
            baseRow(positionId = "reverse-charge").copy(
                taxCategoryCode = "AE", quantity = BigDecimal.ONE, unitPriceNet = Money(BigDecimal("200.00")), taxRatePercent = BigDecimal.ZERO,
                netAmount = Money(BigDecimal("200.00")), vatAmount = Money.ZERO, grossAmount = Money(BigDecimal("200.00")),
            ),
        )
        val bytes = writeWorkbook { writer -> InvoicePositionSheetWriter.write(writer, labels, rows) }

        readWorkbook(bytes).use { wb ->
            val evaluator = wb.creationHelper.createFormulaEvaluator()
            val sheet = wb.getSheet(InvoicePositionSheetWriter.SHEET_NAME)
            rows.forEachIndexed { index, row ->
                val excelRow = sheet.getRow(index + 1)
                val controlNet = evaluator.evaluate(excelRow.getCell(40)).numberValue
                val deltaNet = evaluator.evaluate(excelRow.getCell(41)).numberValue
                val controlVat = evaluator.evaluate(excelRow.getCell(42)).numberValue
                val deltaVat = evaluator.evaluate(excelRow.getCell(43)).numberValue
                val controlGross = evaluator.evaluate(excelRow.getCell(44)).numberValue
                val deltaGross = evaluator.evaluate(excelRow.getCell(45)).numberValue
                val verdict = evaluator.evaluate(excelRow.getCell(46)).stringValue

                assertThat(controlNet).describedAs("controlNet for ${row.positionId}").isEqualTo(row.netAmount.amount.toDouble())
                assertThat(deltaNet).describedAs("deltaNet for ${row.positionId}").isEqualTo(0.0)
                assertThat(controlVat).describedAs("controlVat for ${row.positionId}").isEqualTo(row.vatAmount.amount.toDouble())
                assertThat(deltaVat).describedAs("deltaVat for ${row.positionId}").isEqualTo(0.0)
                assertThat(controlGross).describedAs("controlGross for ${row.positionId}").isEqualTo(row.grossAmount.amount.toDouble())
                assertThat(deltaGross).describedAs("deltaGross for ${row.positionId}").isEqualTo(0.0)
                assertThat(verdict).describedAs("verdict for ${row.positionId}").isEqualTo("OK")
            }
        }
    }

    @Test
    fun `an item whose tax rate is missing evaluates to the Steuersatz fehlt verdict, not a failed export`() {
        val row = baseRow().copy(
            quantity = BigDecimal.ONE,
            unitPriceNet = Money(BigDecimal("100.00")),
            taxRatePercent = null,
            netAmount = Money(BigDecimal("100.00")),
            vatAmount = Money(BigDecimal("19.00")),
            grossAmount = Money(BigDecimal("119.00")),
        )
        val bytes = writeWorkbook { writer -> InvoicePositionSheetWriter.write(writer, labels, listOf(row)) }

        readWorkbook(bytes).use { wb ->
            val evaluator = wb.creationHelper.createFormulaEvaluator()
            val dataRow = wb.getSheet(InvoicePositionSheetWriter.SHEET_NAME).getRow(1)

            assertThat(dataRow.getCell(16).cellType).isEqualTo(CellType.BLANK)
            assertThat(evaluator.evaluate(dataRow.getCell(46)).stringValue).isEqualTo("Steuersatz fehlt")
            // The row is still written with its as-stored amounts, not suppressed or nulled out.
            assertThat(dataRow.getCell(21).numericCellValue).isEqualTo(100.00)
            assertThat(dataRow.getCell(23).numericCellValue).isEqualTo(119.00)
        }
    }

    @Test
    fun `a real deviation (Little Trotter's stored amount disagrees with Excel's own recomputation) evaluates to Abweichung`() {
        val row = baseRow().copy(
            quantity = BigDecimal("2"),
            unitPriceNet = Money(BigDecimal("10.00")),
            taxRatePercent = BigDecimal("19"),
            netAmount = Money(BigDecimal("15.00")), // wrong on purpose: should be 20.00
            vatAmount = Money(BigDecimal("3.80")),
            grossAmount = Money(BigDecimal("23.80")),
        )
        val bytes = writeWorkbook { writer -> InvoicePositionSheetWriter.write(writer, labels, listOf(row)) }

        readWorkbook(bytes).use { wb ->
            val evaluator = wb.creationHelper.createFormulaEvaluator()
            val dataRow = wb.getSheet(InvoicePositionSheetWriter.SHEET_NAME).getRow(1)
            assertThat(evaluator.evaluate(dataRow.getCell(41)).numberValue).isEqualTo(5.0) // 20.00 - 15.00
            assertThat(evaluator.evaluate(dataRow.getCell(46)).stringValue).isEqualTo("Abweichung")
        }
    }

    // ── column widths declared next to the column catalogue ───────────────────────────

    @Test
    fun `COLUMN_WIDTHS has exactly one entry per column`() {
        assertThat(InvoicePositionSheetWriter.COLUMN_WIDTHS).hasSize(InvoicePositionSheetWriter.COLUMN_COUNT)
    }

    @Test
    fun `every money and Δ-money column is width-classed MONEY, every date column DATE`() {
        val widths = InvoicePositionSheetWriter.COLUMN_WIDTHS
        val moneyColumns = listOf(13, 21, 22, 23, 38, 39, 40, 41, 42, 43, 44, 45)
        moneyColumns.forEach { column -> assertThat(widths[column]).describedAs("column $column").isEqualTo(ColumnWidths.MONEY) }
        val dateColumns = listOf(24, 25, 33)
        dateColumns.forEach { column -> assertThat(widths[column]).describedAs("column $column").isEqualTo(ColumnWidths.DATE) }
    }

    @Test
    fun `the free-text columns - itemDescription, exemptionReasonText, taxLegalBasis, receiptFileNames - are width-classed TEXT and wrap`() {
        val widths = InvoicePositionSheetWriter.COLUMN_WIDTHS
        listOf(6, 19, 20, 29).forEach { column -> assertThat(widths[column]).describedAs("column $column").isEqualTo(ColumnWidths.TEXT) }

        val row = baseRow().copy(
            itemDescription = "a long item description",
            exemptionReasonText = "a long exemption reason",
            taxLegalBasis = "a long legal basis reference",
            receiptFileNames = "beleg1.pdf, beleg2.pdf",
        )
        val bytes = writeWorkbook { writer -> InvoicePositionSheetWriter.write(writer, labels, listOf(row)) }
        readWorkbook(bytes).use { wb ->
            val dataRow = wb.getSheet(InvoicePositionSheetWriter.SHEET_NAME).getRow(1)
            listOf(6, 19, 20, 29).forEach { column ->
                assertThat(dataRow.getCell(column).cellStyle.wrapText).describedAs("column $column").isTrue()
            }
        }
    }

    // ── A TRIP position row and its own detail rows ─────────────────────────────────────────────

    private fun tripRow(fixedNetPrice: Money? = null, netAmount: Money = Money(BigDecimal("300.00"))) = InvoicePositionExportRow(
        invoiceId = 1L,
        positionId = "3",
        category = InvoiceItemCategory.TRIP,
        quantity = BigDecimal.ONE,
        unit = InvoiceItemUnit.FLAT_RATE,
        unitPriceNet = netAmount,
        taxCategoryCode = "S",
        taxRatePercent = BigDecimal("19.00"),
        netAmount = netAmount,
        vatAmount = Money(netAmount.amount.multiply(BigDecimal("0.19"))),
        grossAmount = Money(netAmount.amount.multiply(BigDecimal("1.19"))),
        tripFixedNetPrice = fixedNetPrice,
    )

    private fun expenseDetailRow(netAmount: Money, kind: TripExpenseKind = TripExpenseKind.HOTEL) = InvoicePositionExportRow(
        invoiceId = 1L,
        positionId = "3.1",
        category = InvoiceItemCategory.TRIP,
        level = 1,
        parentPositionId = "3",
        detailNumber = "3.1",
        tripKind = kind,
        description = "Hotelübernachtung",
        quantity = BigDecimal.ONE,
        unit = InvoiceItemUnit.PIECE,
        unitPriceNet = netAmount,
        netAmount = netAmount,
    )

    private fun perDiemDetailRow(claimableAmount: Money) = InvoicePositionExportRow(
        invoiceId = 1L,
        positionId = "3.2",
        category = InvoiceItemCategory.TRIP,
        level = 1,
        parentPositionId = "3",
        detailNumber = "3.2",
        travelDate = LocalDate.of(2026, 4, 1),
        quantity = BigDecimal.ONE,
        unit = InvoiceItemUnit.DAY,
        unitPriceNet = claimableAmount,
        netAmount = claimableAmount,
        perDiem = PerDiemDetailColumns(
            isArrivalDeparture = true,
            breakfastDeduction = true,
            lunchDeduction = false,
            dinnerDeduction = false,
            employerContribution = Money(BigDecimal("5.00")),
            claimableAmount = claimableAmount,
        ),
    )

    @Test
    fun `a calculated TRIP row's control net is the SUM of its own detail rows' net amounts`() {
        val rows = listOf(tripRow(), expenseDetailRow(Money(BigDecimal("100.00"))), perDiemDetailRow(Money(BigDecimal("200.00"))))
        val bytes = writeWorkbook { writer -> InvoicePositionSheetWriter.write(writer, labels, rows) }
        readWorkbook(bytes).use { wb ->
            val sheet = wb.getSheet(InvoicePositionSheetWriter.SHEET_NAME)
            val tripDataRow = sheet.getRow(1)
            assertThat(tripDataRow.getCell(40).cellFormula).isEqualTo("SUM(V3:V4)")
            val evaluator = wb.creationHelper.createFormulaEvaluator()
            assertThat(evaluator.evaluate(tripDataRow.getCell(40)).numberValue).isEqualTo(300.0)
            assertThat(evaluator.evaluate(tripDataRow.getCell(41)).numberValue).isEqualTo(0.0)
        }
    }

    @Test
    fun `a fixed-price TRIP row's control net is the fixed price, and Δ is fixed minus the sum of its details`() {
        val rows = listOf(
            tripRow(fixedNetPrice = Money(BigDecimal("250.00")), netAmount = Money(BigDecimal("250.00"))),
            expenseDetailRow(Money(BigDecimal("100.00"))),
            perDiemDetailRow(Money(BigDecimal("200.00"))),
        )
        val bytes = writeWorkbook { writer -> InvoicePositionSheetWriter.write(writer, labels, rows) }
        readWorkbook(bytes).use { wb ->
            val sheet = wb.getSheet(InvoicePositionSheetWriter.SHEET_NAME)
            val tripDataRow = sheet.getRow(1)
            assertThat(tripDataRow.getCell(40).cellFormula).isEqualTo("250.00")
            assertThat(tripDataRow.getCell(41).cellFormula).isEqualTo("AO2-SUM(V3:V4)")
            val evaluator = wb.creationHelper.createFormulaEvaluator()
            // fixed 250.00 - (100.00 + 200.00) = -50.00, the trip's own Δ.
            assertThat(evaluator.evaluate(tripDataRow.getCell(41)).numberValue).isEqualTo(-50.0)
        }
    }

    @Test
    fun `README border case 1 - a fixed-price TRIP row with no expenses or per-diem days shows Δ equal to the fixed price`() {
        val rows = listOf(tripRow(fixedNetPrice = Money(BigDecimal("400.00")), netAmount = Money(BigDecimal("400.00"))))
        val bytes = writeWorkbook { writer -> InvoicePositionSheetWriter.write(writer, labels, rows) }
        readWorkbook(bytes).use { wb ->
            val dataRow = wb.getSheet(InvoicePositionSheetWriter.SHEET_NAME).getRow(1)
            val evaluator = wb.creationHelper.createFormulaEvaluator()
            assertThat(evaluator.evaluate(dataRow.getCell(40)).numberValue).isEqualTo(400.0)
            assertThat(evaluator.evaluate(dataRow.getCell(41)).numberValue).isEqualTo(400.0)
        }
    }

    @Test
    fun `a detail row carries a genuinely blank control block, never a formula`() {
        val rows = listOf(tripRow(), expenseDetailRow(Money(BigDecimal("300.00"))))
        val bytes = writeWorkbook { writer -> InvoicePositionSheetWriter.write(writer, labels, rows) }
        readWorkbook(bytes).use { wb ->
            val detailRow = wb.getSheet(InvoicePositionSheetWriter.SHEET_NAME).getRow(2)
            (40..46).forEach { column -> assertThat(detailRow.getCell(column).cellType).describedAs("column $column").isEqualTo(CellType.BLANK) }
        }
    }

    @Test
    fun `a detail row has no VAT or gross of its own, even though its net amount is real`() {
        val rows = listOf(tripRow(), expenseDetailRow(Money(BigDecimal("300.00"))))
        val bytes = writeWorkbook { writer -> InvoicePositionSheetWriter.write(writer, labels, rows) }
        readWorkbook(bytes).use { wb ->
            val detailRow = wb.getSheet(InvoicePositionSheetWriter.SHEET_NAME).getRow(2)
            assertThat(detailRow.getCell(21).numericCellValue).isEqualTo(300.0) // net
            assertThat(detailRow.getCell(22).cellType).isEqualTo(CellType.BLANK) // vat
            assertThat(detailRow.getCell(23).cellType).isEqualTo(CellType.BLANK) // gross
        }
    }

    @Test
    fun `an expense detail row writes its own kind label, description and travel date - a per-diem day the shared per-diem label and its own block`() {
        val expense = expenseDetailRow(Money(BigDecimal("100.00")), kind = TripExpenseKind.OTHER).copy(travelDate = LocalDate.of(2026, 4, 2))
        val perDiem = perDiemDetailRow(Money(BigDecimal("50.00")))
        val bytes = writeWorkbook { writer -> InvoicePositionSheetWriter.write(writer, labels, listOf(tripRow(), expense, perDiem)) }
        readWorkbook(bytes).use { wb ->
            val sheet = wb.getSheet(InvoicePositionSheetWriter.SHEET_NAME)
            val expenseRow = sheet.getRow(2)
            assertThat(expenseRow.getCell(2).stringCellValue).isEqualTo("3.1") // positionId shows the detail number
            assertThat(expenseRow.getCell(31).stringCellValue).isEqualTo("3") // Übergeordnet
            assertThat(expenseRow.getCell(32).stringCellValue).isEqualTo("Sonstiges") // Art
            assertThat(expenseRow.getCell(33).localDateTimeCellValue.toLocalDate()).isEqualTo(LocalDate.of(2026, 4, 2))
            assertThat(expenseRow.getCell(34).cellType).isEqualTo(CellType.BLANK) // no per-diem block on an expense row

            val perDiemRow = sheet.getRow(3)
            assertThat(perDiemRow.getCell(32).stringCellValue).isEqualTo("Verpflegungspauschale")
            assertThat(perDiemRow.getCell(34).booleanCellValue).isTrue() // arrival/departure
            assertThat(perDiemRow.getCell(35).booleanCellValue).isTrue() // breakfast deduction
            assertThat(perDiemRow.getCell(36).booleanCellValue).isFalse() // lunch deduction
            assertThat(perDiemRow.getCell(38).numericCellValue).isEqualTo(5.00) // employer contribution
            assertThat(perDiemRow.getCell(39).numericCellValue).isEqualTo(50.00) // claimable amount
        }
    }

    @Test
    fun `every cell of a detail row is styled in the secondary (muted) text colour, never the ordinary body colour`() {
        val bytes = writeWorkbook { writer -> InvoicePositionSheetWriter.write(writer, labels, listOf(tripRow(), expenseDetailRow(Money(BigDecimal("300.00"))))) }
        readWorkbook(bytes).use { wb ->
            val positionRow = wb.getSheet(InvoicePositionSheetWriter.SHEET_NAME).getRow(1)
            val detailRow = wb.getSheet(InvoicePositionSheetWriter.SHEET_NAME).getRow(2)
            val positionFontColor = (positionRow.getCell(5).cellStyle as XSSFCellStyle).font.getXSSFColor().argbHex
            val detailFontColor = (detailRow.getCell(5).cellStyle as XSSFCellStyle).font.getXSSFColor().argbHex
            assertThat(detailFontColor).isNotEqualTo(positionFontColor)
        }
    }

    @Test
    fun `the level, Übergeordnet, Art, Reisedatum and every per-diem column all have a width declared`() {
        val widths = InvoicePositionSheetWriter.COLUMN_WIDTHS
        assertThat(widths[30]).isEqualTo(ColumnWidths.FLAG) // Ebene
        assertThat(widths[31]).isEqualTo(ColumnWidths.ID) // Übergeordnet
        assertThat(widths[32]).isEqualTo(ColumnWidths.NAME) // Art
        assertThat(widths[33]).isEqualTo(ColumnWidths.DATE) // Reisedatum
        listOf(34, 35, 36, 37).forEach { column -> assertThat(widths[column]).describedAs("column $column").isEqualTo(ColumnWidths.FLAG) }
        listOf(38, 39).forEach { column -> assertThat(widths[column]).describedAs("column $column").isEqualTo(ColumnWidths.MONEY) }
    }
}
