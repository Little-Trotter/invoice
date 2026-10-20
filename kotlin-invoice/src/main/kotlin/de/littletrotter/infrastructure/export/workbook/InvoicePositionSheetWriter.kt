package de.littletrotter.infrastructure.export.workbook

import de.littletrotter.application.export.InvoicePositionExportRow
import de.littletrotter.shared.tax.VatCategory
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.Money

/**
 * Writes the `Positionen` sheet: one row per [InvoicePositionExportRow], in the fixed
 * column order, followed by the R4 control-formula block (real Excel
 * `ROUND`/`IF` formulas, computed by Excel itself, mirroring the exact rounding rule
 * [de.littletrotter.shared.Money] applies — `HALF_UP` at two decimals, applied at each step).
 *
 * Every enum value is resolved to a caller-supplied label via [InvoicePositionSheetLabels] — never
 * an ordinal, never [Enum.name] — matching [InvoiceHeaderSheetWriter]'s own rule.
 *
 * A `TRIP` item contributes a level-0 position row (written exactly like any other item's)
 * immediately followed by its own level-1 detail rows (one per [de.littletrotter.domain.invoice
 * .TripExpense], then one per per-diem day — [de.littletrotter.application.export
 * .InvoicePositionRowMapper.tripDetailRows] builds them in that order, this writer never reorders
 * rows). A detail row writes in [CellStyles.secondaryText]/[CellStyles.secondaryMoney]/… styles
 * (never the ordinary ones) and carries no tax/VAT/gross columns or R4 control block of its own — a
 * `TRIP` row's own control block instead compares its net against the `SUM` of its detail rows' net
 * amounts (or, when [InvoicePositionExportRow.tripFixedNetPrice] is set, shows that fixed price and
 * a Δ of `fixed − Σ details`), so the sub-lines this writer adds are proven right where the rest of
 * this sheet's own R4 arithmetic already lives, not merely reported.
 */
object InvoicePositionSheetWriter {

    /** One row's own place within the full sheet — [row] itself, the complete [rows] list (a
     *  `TRIP` level-0 row looks ahead at its own following detail rows in it) and its [index].
     *  Bundled so [writeControlBlock]/[tripControlNetFormula] each take one parameter for it
     *  instead of three (LongParameterList). */
    private data class RowPosition(
        val row: InvoicePositionExportRow,
        val rows: List<InvoicePositionExportRow>,
        val index: Int,
    )

    const val SHEET_NAME = "Positionen"

    /** Total column count of the sheet (data columns + the seven control-block columns). */
    const val COLUMN_COUNT = 47

    /** Number of data columns before the seven-column R4 control-formula block starts — exactly
     *  where [de.littletrotter.infrastructure.export.csv.InvoicePositionCsvWriter] stops: CSV has
     *  no formulas, so the CSV export flattens this sheet down to its first [DATA_COLUMN_COUNT] columns and
     *  nothing past them. */
    const val DATA_COLUMN_COUNT = COLUMN_COUNT - 7

    /** Excel is 1-based and row 1 is the header, so the first data row is row 2. */
    private const val FIRST_DATA_ROW = 2

    private const val DEVIATION_TOLERANCE = "0.005"

    fun write(writer: WorkbookWriter, labels: InvoicePositionSheetLabels, rows: List<InvoicePositionExportRow>): SheetWriter {
        require(labels.columnHeaders.size == COLUMN_COUNT) {
            "InvoicePositionSheetLabels.columnHeaders must have $COLUMN_COUNT entries, " +
                "got ${labels.columnHeaders.size}"
        }
        val sheet = writer.sheet(SHEET_NAME)
        sheet.header(labels.columnHeaders, COLUMN_WIDTHS)
        rows.forEachIndexed { index, row ->
            val secondary = row.level == LEVEL_DETAIL
            sheet.row()
                .writeLinkAndOrder(row, secondary)
                .writeDescription(row, labels, secondary)
                .writeArticleIdentifiers(row, secondary)
                .writeQuantityAndPrice(row, secondary)
                .writeTax(row, labels)
                .writeAmounts(row, secondary)
                .writeContext(row, secondary)
                .writeTripDetailColumns(row, labels, secondary)
                .writeControlBlock(RowPosition(row, rows, index), index + FIRST_DATA_ROW, labels)
        }
        // Same shared rule the header sheet's own Δ columns now carry (moved out of a
        // static cell-style highlight into a genuine conditional-formatting rule).
        sheet.markDeviationColumns(listOf(Col.DELTA_NET, Col.DELTA_VAT, Col.DELTA_GROSS))
        return sheet
    }

    private const val LEVEL_DETAIL = 1

    /** Column indices the control-formula block (and this sheet's own trip-detail lookups) need to
     *  address by letter — every other column is written strictly in sequence via the chained
     *  [RowWriter] calls above. */
    private object Col {
        const val QUANTITY = 10
        const val UNIT_PRICE_NET = 13
        const val TAX_RATE_PERCENT = 16
        const val NET_AMOUNT = 21
        const val VAT_AMOUNT = 22
        const val GROSS_AMOUNT = 23
        const val CONTROL_NET = 40
        const val DELTA_NET = 41
        const val CONTROL_VAT = 42
        const val DELTA_VAT = 43
        const val CONTROL_GROSS = 44
        const val DELTA_GROSS = 45
    }

    /** One width class per column, in [Col]'s own order (link/order, description,
     *  article identifiers, quantity/price, tax, amounts, context, the trip position/detail
     *  columns, then the seven-column control block). */
    internal val COLUMN_WIDTHS: List<Int> = listOf(
        // Link and order: invoice id, invoice number, position id, sort order.
        ColumnWidths.ID, ColumnWidths.CODE, ColumnWidths.ID, ColumnWidths.ID,
        // Description: category, description, item description (free text, wrapped).
        ColumnWidths.NAME, ColumnWidths.NAME, ColumnWidths.TEXT,
        // Article identifiers.
        ColumnWidths.CODE, ColumnWidths.CODE, ColumnWidths.CODE,
        // Quantity and price.
        ColumnWidths.QUANTITY, ColumnWidths.NAME, ColumnWidths.CODE, ColumnWidths.MONEY,
        // Tax.
        ColumnWidths.FLAG, ColumnWidths.NAME, ColumnWidths.PERCENT, ColumnWidths.CODE,
        ColumnWidths.CODE, ColumnWidths.TEXT, ColumnWidths.TEXT,
        // Amounts as stored.
        ColumnWidths.MONEY, ColumnWidths.MONEY, ColumnWidths.MONEY,
        // Context.
        ColumnWidths.DATE, ColumnWidths.DATE, ColumnWidths.CODE, ColumnWidths.CODE,
        ColumnWidths.ID, ColumnWidths.TEXT,
        // Trip position/detail: level, parent link, kind, travel date.
        ColumnWidths.FLAG, ColumnWidths.ID, ColumnWidths.NAME, ColumnWidths.DATE,
        // Per-diem block: arrival/departure, three deductions, employer contribution,
        // claimable amount.
        ColumnWidths.FLAG, ColumnWidths.FLAG, ColumnWidths.FLAG, ColumnWidths.FLAG,
        ColumnWidths.MONEY, ColumnWidths.MONEY,
        // Control block: Kontrolle/Δ Netto, Steuer, Brutto, then the verdict.
        ColumnWidths.MONEY, ColumnWidths.MONEY, ColumnWidths.MONEY, ColumnWidths.MONEY,
        ColumnWidths.MONEY, ColumnWidths.MONEY, ColumnWidths.NAME,
    )

    private fun RowWriter.writeLinkAndOrder(row: InvoicePositionExportRow, secondary: Boolean): RowWriter =
        integer(row.invoiceId, secondary)
            .text(row.invoiceNumber.ifBlank { null }, secondary = secondary)
            .text(row.positionId, secondary = secondary)
            .integer(row.sortOrder.toLong(), secondary)

    private fun RowWriter.writeDescription(row: InvoicePositionExportRow, labels: InvoicePositionSheetLabels, secondary: Boolean): RowWriter =
        text(labels.enumLabels.category.labelFor(row.category), secondary = secondary)
            .text(row.description, secondary = secondary)
            .text(row.itemDescription, wrap = true, secondary = secondary)

    private fun RowWriter.writeArticleIdentifiers(row: InvoicePositionExportRow, secondary: Boolean): RowWriter =
        text(row.sellerItemIdentifier, secondary = secondary)
            .text(row.buyerItemIdentifier, secondary = secondary)
            .text(row.articleNumber, secondary = secondary)

    private fun RowWriter.writeQuantityAndPrice(row: InvoicePositionExportRow, secondary: Boolean): RowWriter =
        quantity(row.quantity, secondary)
            .text(row.unit.symbol, secondary = secondary)
            .text(row.unit.uneceCode, secondary = secondary)
            .money(row.unitPriceNet, row.currency, secondary)

    /** A level-1 detail row never has tax fields (its own [InvoicePositionExportRow] always
     *  carries `null` in every one of them — [de.littletrotter.application.export
     *  .InvoicePositionRowMapper.tripDetailRows] never sets them), so this writes seven genuinely
     *  blank cells for it without any special-casing here; unchanged for a level-0 row. */
    private fun RowWriter.writeTax(row: InvoicePositionExportRow, labels: InvoicePositionSheetLabels): RowWriter =
        text(row.taxCategoryCode)
            .text(taxCategoryLabel(row, labels))
            .percent(row.taxRatePercent)
            .text(row.taxRuleCode)
            .text(row.exemptionReasonCode)
            .text(row.exemptionReasonText, wrap = true)
            .text(row.taxLegalBasis, wrap = true)

    /** A level-1 detail row's own net amount is real (its trip's control block sums it), but
     *  it carries no VAT/gross of its own ("no tax columns", rk-6 item 2) — those two cells are
     *  written genuinely blank rather than a misleading `0,00`. */
    private fun RowWriter.writeAmounts(row: InvoicePositionExportRow, secondary: Boolean): RowWriter {
        val afterNet = money(row.netAmount, row.currency, secondary)
        return if (secondary) afterNet.blank().blank() else afterNet.money(row.vatAmount, row.currency).money(row.grossAmount, row.currency)
    }

    private fun RowWriter.writeContext(row: InvoicePositionExportRow, secondary: Boolean): RowWriter =
        date(row.servicePeriodFrom, secondary)
            .date(row.servicePeriodTo, secondary)
            .text(row.costCenter, secondary = secondary)
            .text(row.deliveryNoteReference, secondary = secondary)
            .integer(row.receiptsCount.toLong(), secondary)
            .text(row.receiptFileNames, wrap = true, secondary = secondary)

    /** The new trip position/detail columns: `Ebene`, `Übergeordnet`, `Art`, `Reisedatum`,
     *  then the per-diem block. [InvoicePositionExportRow.perDiem] and
     *  [InvoicePositionExportRow.tripKind] are mutually exclusive on a level-1 row (never both set)
     *  and both `null` on a level-0 row — [artLabel] resolves whichever of the two is present, the
     *  per-diem block writes six blank cells when [InvoicePositionExportRow.perDiem] is absent. */
    private fun RowWriter.writeTripDetailColumns(row: InvoicePositionExportRow, labels: InvoicePositionSheetLabels, secondary: Boolean): RowWriter {
        val withCore = integer(row.level.toLong(), secondary)
            .text(row.parentPositionId, secondary = secondary)
            .text(artLabel(row, labels), secondary = secondary)
            .date(row.travelDate, secondary)
        val perDiem = row.perDiem ?: return withCore.blank().blank().blank().blank().blank().blank()
        return withCore
            .bool(perDiem.isArrivalDeparture, secondary)
            .bool(perDiem.breakfastDeduction, secondary)
            .bool(perDiem.lunchDeduction, secondary)
            .bool(perDiem.dinnerDeduction, secondary)
            .money(perDiem.employerContribution, row.currency, secondary)
            .money(perDiem.claimableAmount, row.currency, secondary)
    }

    /** The "Art" column's label: a per-diem day always resolves to [InvoicePositionSheetLabels
     *  .perDiemKindLabel] (never [InvoicePositionEnumLabels.tripExpenseKind] — there is no
     *  [de.littletrotter.domain.invoice.TripExpenseKind] for a per-diem day), a trip expense to its
     *  own kind's label, and every other row (a level-0 row of any category) to `null` — a blank
     *  cell, this column only ever describes a detail row. */
    private fun artLabel(row: InvoicePositionExportRow, labels: InvoicePositionSheetLabels): String? = when {
        row.perDiem != null -> labels.perDiemKindLabel
        row.tripKind != null -> labels.enumLabels.tripExpenseKind.labelFor(row.tripKind)
        else -> null
    }

    /** The frozen UNTDID 5305 code (`taxCategoryCode`) resolved to its human label via
     *  [VatCategory.fromCodeOrNull]; a `null`/unrecognised code writes a blank cell, never a
     *  fallback name (unlike [labelFor]'s enum-name fallback — there is no [VatCategory] instance
     *  to fall back onto for a code the codebase does not know).
     *
     *  `internal`, not `private`: [de.littletrotter.infrastructure.export.csv
     *  .InvoicePositionCsvWriter] calls this directly rather than re-deriving the same
     *  lookup, so the CSV's `Tax category` column can never drift from this sheet's own. */
    internal fun taxCategoryLabel(row: InvoicePositionExportRow, labels: InvoicePositionSheetLabels): String? =
        VatCategory.fromCodeOrNull(row.taxCategoryCode)?.let { labels.enumLabels.vatCategory.labelFor(it) }

    /**
     * R4 — the seven control-formula columns: three `ROUND`-based "Kontrolle" columns mirroring
     * the application's own rounding rule exactly (`ROUND(qty*price;2)`, then
     * `ROUND(net*rate/100;2)`, then `net+tax`), three "Δ" columns comparing them against this
     * row's own stored figures, and the `Prüfung` text verdict. Every formula is relative to
     * [excelRow] (this row's own 1-based Excel row number), matching
     * [InvoiceHeaderSheetWriter.writeControlBlock]'s own convention.
     *
     * The verdict formula checks `ISBLANK` on the `Steuersatz %` cell itself before the usual
     * deviation check — an empty rate (a missing-tax-rate border case) is a data-completeness
     * finding, not an arithmetic one, and must read "Steuersatz fehlt" even though the blank rate
     * makes `Kontrolle Steuer` compute as if the rate were `0` (Excel treats a blank cell as `0` in
     * arithmetic) and therefore usually produces a non-zero Δ of its own.
     *
     * A level-1 detail row gets a genuinely blank control block (there is nothing of its own
     * to verify: it is a component of its trip's own control, not an invoice line with its own tax
     * treatment). A level-0 `TRIP` row's own "Kontrolle Netto"/"Δ Netto" pair is not the usual
     * `ROUND(qty*price)` — quantity is always `1` and the unit price always equals the stored net
     * for a `TRIP` item ([de.littletrotter.domain.invoice.InvoiceItem.calculateAmounts]), which
     * would make that formula a tautology — but [tripControlNetFormula]'s own `SUM` of the trip's
     * detail rows, or (fixed price set) the fixed price itself, per rk-6 item 2 and README border
     * case 1.
     */
    private fun RowWriter.writeControlBlock(
        pos: RowPosition,
        excelRow: Int,
        labels: InvoicePositionSheetLabels,
    ): RowWriter {
        val row = pos.row
        if (row.level == LEVEL_DETAIL) return blank().blank().blank().blank().blank().blank().blank()

        val quantityCol = ExcelColumnLetters.of(Col.QUANTITY)
        val unitPriceCol = ExcelColumnLetters.of(Col.UNIT_PRICE_NET)
        val taxRateCol = ExcelColumnLetters.of(Col.TAX_RATE_PERCENT)
        val netCol = ExcelColumnLetters.of(Col.NET_AMOUNT)
        val vatCol = ExcelColumnLetters.of(Col.VAT_AMOUNT)
        val grossCol = ExcelColumnLetters.of(Col.GROSS_AMOUNT)
        val controlNetCol = ExcelColumnLetters.of(Col.CONTROL_NET)
        val deltaNetCol = ExcelColumnLetters.of(Col.DELTA_NET)
        val controlVatCol = ExcelColumnLetters.of(Col.CONTROL_VAT)
        val deltaVatCol = ExcelColumnLetters.of(Col.DELTA_VAT)
        val controlGrossCol = ExcelColumnLetters.of(Col.CONTROL_GROSS)
        val deltaGrossCol = ExcelColumnLetters.of(Col.DELTA_GROSS)

        val (controlNetFormula, deltaNetFormula) = if (row.category == InvoiceItemCategory.TRIP) {
            tripControlNetFormula(pos, excelRow, netCol, controlNetCol)
        } else {
            "ROUND($quantityCol$excelRow*$unitPriceCol$excelRow,2)" to "$controlNetCol$excelRow-$netCol$excelRow"
        }

        return formulaMoney(controlNetFormula)
            .formulaDeviation(deltaNetFormula)
            .formulaMoney("ROUND($controlNetCol$excelRow*$taxRateCol$excelRow/100,2)")
            .formulaDeviation("$controlVatCol$excelRow-$vatCol$excelRow")
            .formulaMoney("$controlNetCol$excelRow+$controlVatCol$excelRow")
            .formulaDeviation("$controlGrossCol$excelRow-$grossCol$excelRow")
            .formula(
                "IF(ISBLANK($taxRateCol$excelRow),${excelStringLiteral(labels.verdictTaxRateMissingLabel)}," +
                    "IF(MAX(ABS($deltaNetCol$excelRow),ABS($deltaVatCol$excelRow),ABS($deltaGrossCol$excelRow))>$DEVIATION_TOLERANCE," +
                    "${excelStringLiteral(labels.verdictDeviationLabel)},${excelStringLiteral(labels.verdictOkLabel)}))",
            )
    }

    /**
     * A `TRIP` row's own "Kontrolle Netto"/"Δ Netto" formulas: with no fixed
     * price, "Kontrolle Netto" is the `SUM` of the trip's own detail rows' net amounts (`0` for a
     * trip with none — an invalid state in practice, but never a `#REF!`/division error) and "Δ
     * Netto" compares that sum against the stored net, exactly like every other category's Δ. With
     * a fixed price, "Kontrolle Netto" shows the fixed price itself (not the sum) and "Δ Netto" is
     * `fixed − Σ details` directly — README border case 1's own "Δ = Festpreis" for a trip with no
     * expenses at all falls out of this for free (`Σ details` is `0`).
     */
    private fun tripControlNetFormula(
        pos: RowPosition,
        excelRow: Int,
        netCol: String,
        controlNetCol: String,
    ): Pair<String, String> {
        val sumOfDetails = detailNetRange(pos.rows, pos.index)?.let { "SUM($it)" } ?: "0"
        val fixed = pos.row.tripFixedNetPrice
        return if (fixed != null) {
            moneyLiteral(fixed) to "$controlNetCol$excelRow-$sumOfDetails"
        } else {
            sumOfDetails to "$controlNetCol$excelRow-$netCol$excelRow"
        }
    }

    /** The `V<first>:V<last>` range of [rows]' own level-0 row at [index]'s immediately following
     *  level-1 detail rows — [de.littletrotter.application.export.InvoicePositionRowMapper
     *  .tripDetailRows] always emits them contiguously right after their own position row, so a
     *  forward scan until the next level-0 row (or the end of [rows]) finds exactly them. `null`
     *  when there are none (README border case 1: fixed price only, no expenses or per-diem days). */
    private fun detailNetRange(rows: List<InvoicePositionExportRow>, index: Int): String? {
        var next = index + 1
        while (next < rows.size && rows[next].level == LEVEL_DETAIL) next++
        if (next == index + 1) return null
        val netCol = ExcelColumnLetters.of(Col.NET_AMOUNT)
        val firstExcelRow = (index + 1) + FIRST_DATA_ROW
        val lastExcelRow = (next - 1) + FIRST_DATA_ROW
        return "$netCol$firstExcelRow:$netCol$lastExcelRow"
    }

    /** A [Money] amount as a bare Excel formula literal (`"1234.56"`, `"-50.00"`) — always `.`
     *  decimal-separated regardless of display locale, matching how POI itself stores every
     *  formula string (the sheet's own `ROUND`/`IF` formulas are never locale-sensitive either). */
    private fun moneyLiteral(value: Money): String = value.amount.toPlainString()

    /** Excel string-literal escaping: an embedded `"` doubles, per the spreadsheet formula
     *  grammar — defensive, since the verdict labels are ordinary i18n text (matches
     *  [InvoiceHeaderSheetWriter]'s own helper). */
    private fun excelStringLiteral(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
}
