package de.littletrotter.infrastructure.export.workbook

import de.littletrotter.application.export.InvoiceHeaderExportRow
import java.text.MessageFormat

/**
 * Writes the `Rechnungen` sheet: one row per [InvoiceHeaderExportRow], in the fixed column
 * order, followed by the R4 control-formula block (real Excel
 * `SUMIF`/`COUNTIF`/`IF` formulas, computed by Excel itself, never a pre-computed number — that is
 * the entire point of the block).
 *
 * The control block needs the `Positionen` sheet's own row count and column layout
 * (positionSheetLastDataRow/positionColumns) to build its `SUMIF`/`COUNTIF` ranges, even though
 * this package does not write that sheet: both sheets link by invoice id (R1), and a
 * whole-column `SUMIF` over a quarter-million rows would make the workbook unpleasant to open.
 *
 * Every enum value is resolved to a caller-supplied label via [InvoiceHeaderSheetLabels] — never an
 * ordinal, never [Enum.name].
 */
object InvoiceHeaderSheetWriter {

    const val SHEET_NAME = "Rechnungen"

    /** Total column count of the sheet (data columns + the nine control-block columns). */
    const val COLUMN_COUNT = 70

    /** Excel is 1-based and row 1 is the header, so the first data row is row 2. */
    private const val FIRST_DATA_ROW = 2

    private const val POSITIONS_PREFIX = "Positionen!"

    private const val DEVIATION_TOLERANCE = "0.005"

    fun write(
        writer: WorkbookWriter,
        labels: InvoiceHeaderSheetLabels,
        rows: List<InvoiceHeaderExportRow>,
        positionSheetLastDataRow: Int,
        positionColumns: PositionSheetReferenceColumns = PositionSheetReferenceColumns(),
    ): SheetWriter {
        require(labels.columnHeaders.size == COLUMN_COUNT) {
            "InvoiceHeaderSheetLabels.columnHeaders must have $COLUMN_COUNT entries, " +
                "got ${labels.columnHeaders.size}"
        }
        val sheet = writer.sheet(SHEET_NAME)
        sheet.header(labels.columnHeaders, COLUMN_WIDTHS)
        rows.forEachIndexed { index, row ->
            sheet.row()
                .writeIdentification(row, labels)
                .writeDates(row)
                .writePaymentTerm(row)
                .writeSeller(row)
                .writeBuyer(row, labels)
                .writeReferences(row)
                .writeStorno(row)
                .writeAmounts(row)
                .writeAudit(row)
                .writeControlBlock(index + FIRST_DATA_ROW, positionSheetLastDataRow, positionColumns, labels)
        }
        // The header sheet's own Δ columns share the conditional-formatting rule
        // rather than the earlier static highlight (moved here so it is built exactly once).
        sheet.markDeviationColumns(listOf(Col.DELTA_NET, Col.DELTA_VAT, Col.DELTA_GROSS, Col.DELTA_ITEMS))
        return sheet
    }

    /** Column indices the control-formula block (only) needs to address by letter — every other
     *  column is written strictly in sequence via the chained [RowWriter] calls above. */
    private object Col {
        const val ID = 0
        const val NET_AMOUNT = 49
        const val VAT_AMOUNT = 50
        const val GROSS_AMOUNT = 51
        const val ITEMS_COUNT = 52
        const val CONTROL_NET = 61
        const val DELTA_NET = 62
        const val CONTROL_VAT = 63
        const val DELTA_VAT = 64
        const val CONTROL_GROSS = 65
        const val DELTA_GROSS = 66
        const val CONTROL_ITEMS = 67
        const val DELTA_ITEMS = 68
    }

    /**
     * One width class per column, in [Col]'s own order (identification, dates, payment
     * term, seller, buyer, references, storno, amounts, audit, then the nine-column control
     * block) — [SheetWriter.header]'s own `require` catches a column added to one list but
     * forgotten in the other.
     */
    internal val COLUMN_WIDTHS: List<Int> = listOf(
        // Identification: id, invoice number, prefix, sequence, type code/label, status,
        // validation status, invoice format, document language, document engine.
        ColumnWidths.ID, ColumnWidths.CODE, ColumnWidths.CODE, ColumnWidths.ID,
        ColumnWidths.FLAG, ColumnWidths.NAME, ColumnWidths.NAME, ColumnWidths.NAME, ColumnWidths.NAME,
        ColumnWidths.FLAG, ColumnWidths.NAME,
        // Dates and payment term.
        ColumnWidths.DATE, ColumnWidths.DATE, ColumnWidths.DATE, ColumnWidths.DATE, ColumnWidths.DATE,
        ColumnWidths.ID, ColumnWidths.NAME, ColumnWidths.PERCENT, ColumnWidths.ID,
        // Seller.
        ColumnWidths.NAME, ColumnWidths.CODE, ColumnWidths.CODE, ColumnWidths.CODE, ColumnWidths.FLAG,
        ColumnWidths.CODE, ColumnWidths.FLAG, ColumnWidths.NAME, ColumnWidths.FLAG, ColumnWidths.FLAG,
        // Buyer.
        ColumnWidths.NAME, ColumnWidths.CODE, ColumnWidths.CODE, ColumnWidths.FLAG,
        ColumnWidths.NAME, ColumnWidths.FLAG, ColumnWidths.NAME,
        // References (BT block).
        ColumnWidths.CODE, ColumnWidths.CODE, ColumnWidths.CODE, ColumnWidths.DATE,
        ColumnWidths.CODE, ColumnWidths.DATE, ColumnWidths.CODE, ColumnWidths.CODE,
        // Storno reference.
        ColumnWidths.CODE, ColumnWidths.DATE,
        // Tax and amounts.
        ColumnWidths.FLAG, ColumnWidths.FLAG, ColumnWidths.MONEY, ColumnWidths.MONEY, ColumnWidths.MONEY,
        ColumnWidths.ID, ColumnWidths.ID, ColumnWidths.FLAG,
        // Audit.
        ColumnWidths.DATE_TIME, ColumnWidths.NAME, ColumnWidths.DATE_TIME, ColumnWidths.NAME,
        ColumnWidths.ID, ColumnWidths.TEXT,
        // Control block: Kontrolle/Δ Netto, Steuer, Brutto, Positionen, then the verdict.
        ColumnWidths.MONEY, ColumnWidths.MONEY, ColumnWidths.MONEY, ColumnWidths.MONEY,
        ColumnWidths.MONEY, ColumnWidths.MONEY, ColumnWidths.ID, ColumnWidths.QUANTITY, ColumnWidths.NAME,
    )

    private fun RowWriter.writeIdentification(row: InvoiceHeaderExportRow, labels: InvoiceHeaderSheetLabels): RowWriter =
        integer(row.invoiceId)
            .text(invoiceNumberCellValue(row, labels))
            .text(row.invoiceNumberPrefix)
            .integer(row.invoiceNumberSequence)
            .text(row.typeCode.code)
            .text(labels.enumLabels.typeCode.labelFor(row.typeCode))
            .text(labels.enumLabels.status.labelFor(row.status))
            .text(labels.enumLabels.validationStatus.labelFor(row.validationStatus))
            .text(labels.enumLabels.invoiceFormat.labelFor(row.invoiceFormat))
            .text(row.documentLanguage)
            .text(labels.enumLabels.documentEngine.labelFor(row.documentEngine))

    private fun RowWriter.writeDates(row: InvoiceHeaderExportRow): RowWriter =
        date(row.invoiceDate)
            .date(row.deliveryDate)
            .date(row.servicePeriodFrom)
            .date(row.servicePeriodTo)
            .date(row.dueDate)
            .integer(row.paymentTermsDays.toLong())

    private fun RowWriter.writePaymentTerm(row: InvoiceHeaderExportRow): RowWriter =
        text(row.paymentTermSnapshotName)
            .percent(row.paymentTermSnapshotDiscountPercent)
            .integer(row.paymentTermSnapshotDiscountDays?.toLong())

    private fun RowWriter.writeSeller(row: InvoiceHeaderExportRow): RowWriter =
        text(row.sellerName)
            .text(row.sellerVatId)
            .text(row.sellerTaxNumber)
            .text(row.sellerIdentifierValue)
            .text(row.sellerIdentifierSchemeId)
            .text(row.sellerRegisterNumber)
            .text(row.sellerRegisterSchemeId)
            .text(row.sellerElectronicAddressValue)
            .text(row.sellerElectronicAddressSchemeId)
            .text(row.sellerVatRegistrationCountry)

    private fun RowWriter.writeBuyer(row: InvoiceHeaderExportRow, labels: InvoiceHeaderSheetLabels): RowWriter =
        text(row.buyerName)
            .text(row.buyerVatId)
            .text(row.buyerIdentifierValue)
            .text(row.buyerIdentifierSchemeId)
            .text(row.buyerElectronicAddressValue)
            .text(row.buyerElectronicAddressSchemeId)
            .text(labels.enumLabels.partySource.labelFor(row.partySource))

    private fun RowWriter.writeReferences(row: InvoiceHeaderExportRow): RowWriter =
        text(row.buyerReference)
            .text(row.purchaseOrderReference)
            .text(row.orderReference)
            .date(row.orderReferenceDate)
            .text(row.offerReference)
            .date(row.offerReferenceDate)
            .text(row.projectNumber)
            .text(row.costCenter)

    private fun RowWriter.writeStorno(row: InvoiceHeaderExportRow): RowWriter =
        text(row.originalInvoiceNumber).date(row.originalInvoiceDate)

    private fun RowWriter.writeAmounts(row: InvoiceHeaderExportRow): RowWriter =
        text(row.currency.code)
            .bool(row.reverseCharge)
            .money(row.netAmount, row.currency)
            .money(row.vatAmount, row.currency)
            .money(row.grossAmount, row.currency)
            .integer(row.itemsCount.toLong())
            .integer(row.receiptsCount.toLong())
            .bool(row.needsRecalculation)

    private fun RowWriter.writeAudit(row: InvoiceHeaderExportRow): RowWriter =
        dateTime(row.createdAt)
            .text(row.createdBy)
            .dateTime(row.updatedAt)
            .text(row.updatedBy)
            .integer(row.version)
            .text(row.freeText, wrap = true)

    /** R4 — the nine control-formula columns: three `SUMIF`/`COUNTIF` "Kontrolle" columns read
     *  from the `Positionen` sheet, four "Δ" columns comparing them against this row's own stored
     *  figures, and the `Prüfung` text verdict. Every formula is relative to [excelRow] (this
     *  row's own 1-based Excel row number) so copying the sheet works the way any normal Excel
     *  sheet does. */
    private fun RowWriter.writeControlBlock(
        excelRow: Int,
        positionSheetLastDataRow: Int,
        positionColumns: PositionSheetReferenceColumns,
        labels: InvoiceHeaderSheetLabels,
    ): RowWriter {
        val idCol = ExcelColumnLetters.of(Col.ID)
        val netCol = ExcelColumnLetters.of(Col.NET_AMOUNT)
        val vatCol = ExcelColumnLetters.of(Col.VAT_AMOUNT)
        val grossCol = ExcelColumnLetters.of(Col.GROSS_AMOUNT)
        val itemsCol = ExcelColumnLetters.of(Col.ITEMS_COUNT)
        val controlNetCol = ExcelColumnLetters.of(Col.CONTROL_NET)
        val controlVatCol = ExcelColumnLetters.of(Col.CONTROL_VAT)
        val controlGrossCol = ExcelColumnLetters.of(Col.CONTROL_GROSS)
        val controlItemsCol = ExcelColumnLetters.of(Col.CONTROL_ITEMS)
        val deltaNetCol = ExcelColumnLetters.of(Col.DELTA_NET)
        val deltaVatCol = ExcelColumnLetters.of(Col.DELTA_VAT)
        val deltaGrossCol = ExcelColumnLetters.of(Col.DELTA_GROSS)
        val deltaItemsCol = ExcelColumnLetters.of(Col.DELTA_ITEMS)

        val idRange = POSITIONS_PREFIX + absoluteRange(positionColumns.invoiceIdColumn, positionSheetLastDataRow)
        val netRange = POSITIONS_PREFIX + absoluteRange(positionColumns.netAmountColumn, positionSheetLastDataRow)
        val vatRange = POSITIONS_PREFIX + absoluteRange(positionColumns.vatAmountColumn, positionSheetLastDataRow)
        // A trip's own detail rows share its invoiceId, so every SUMIF/COUNTIF became
        // SUMIFS/COUNTIFS with this second criterion (level = 0) the moment the Positionen sheet
        // started carrying them, or a trip's parts would be counted twice (README border case 8's
        // own "invoice-level control sums only level-0 rows" rule).
        val levelRange = POSITIONS_PREFIX + absoluteRange(positionColumns.levelColumn, positionSheetLastDataRow)
        val thisRowId = absoluteCell(idCol, excelRow)

        return formulaMoney("SUMIFS($netRange,$idRange,$thisRowId,$levelRange,0)")
            .formulaDeviation("$controlNetCol$excelRow-$netCol$excelRow")
            .formulaMoney("SUMIFS($vatRange,$idRange,$thisRowId,$levelRange,0)")
            .formulaDeviation("$controlVatCol$excelRow-$vatCol$excelRow")
            .formulaMoney("$controlNetCol$excelRow+$controlVatCol$excelRow")
            .formulaDeviation("$controlGrossCol$excelRow-$grossCol$excelRow")
            .formulaInteger("COUNTIFS($idRange,$thisRowId,$levelRange,0)")
            .formulaDeviationInteger("$controlItemsCol$excelRow-$itemsCol$excelRow")
            .formula(
                "IF(MAX(ABS($deltaNetCol$excelRow),ABS($deltaVatCol$excelRow),ABS($deltaGrossCol$excelRow)," +
                    "ABS($deltaItemsCol$excelRow))>$DEVIATION_TOLERANCE," +
                    "${excelStringLiteral(labels.verdictDeviationLabel)},${excelStringLiteral(labels.verdictOkLabel)})",
            )
    }

    /** Column-absolute, row-RELATIVE (`$A2`, never `$A$2`) — matching the sheet's own
     *  `SUMIF` example exactly: this cell reference is copied down one row per invoice row, so it
     *  must always point at *this* row's own id, not always at row 2's. */
    private fun absoluteCell(column: String, row: Int): String = "$$column$row"

    private fun absoluteRange(column: String, lastDataRow: Int): String =
        "$$column$2:$$column$$lastDataRow"

    /** Excel string-literal escaping: an embedded `"` doubles, per the spreadsheet formula
     *  grammar — defensive, since [InvoiceHeaderSheetLabels.verdictOkLabel]/
     *  [InvoiceHeaderSheetLabels.verdictDeviationLabel] are ordinary i18n text. */
    private fun excelStringLiteral(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

    private fun invoiceNumberCellValue(row: InvoiceHeaderExportRow, labels: InvoiceHeaderSheetLabels): String? =
        if (row.invoiceNumberMalformed) {
            MessageFormat.format(labels.malformedNumberMarkerPattern, row.invoiceNumber)
        } else {
            row.invoiceNumber.ifBlank { null }
        }
}
