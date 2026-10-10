package de.littletrotter.infrastructure.export.workbook.trip

import de.littletrotter.application.export.TripWorkbookView
import de.littletrotter.infrastructure.export.workbook.ColumnWidths
import de.littletrotter.infrastructure.export.workbook.ExcelColumnLetters
import de.littletrotter.infrastructure.export.workbook.SheetWriter
import de.littletrotter.infrastructure.export.workbook.WorkbookWriter
import java.text.MessageFormat

/**
 * Writes the "Dienstreise" sheet: one key/value row per field of the trip position's own
 * header data (title, purpose, period, invoice reference, customer, cost centre, tax treatment,
 * the two candidate net prices and their Δ, the receipt count) — as opposed to
 * [TripExpenseSheetWriter]'s per-line detail.
 *
 * A genuine two-column tabular sheet (`header`, not [SheetWriter.columnWidths]'s cover-sheet
 * idiom, the way [de.littletrotter.infrastructure.export.workbook.ExportSheetWriter] uses it): this
 * is a real, if short, list of fields, not a title page. Because the "Wert" column mixes text,
 * dates, money and one formula cell down its own rows, [SheetWriter.markDeviationCell] highlights
 * exactly the Δ row rather than [SheetWriter.markDeviationColumns]' whole-column rule — most of
 * this column's other rows are not comparable numbers at all (`ABS` of a text/date cell would
 * simply fail to match the rule, which is safe, but two of the other rows — the calculated and
 * fixed prices themselves — are ordinary non-zero money values that a whole-column rule would
 * wrongly flag as "deviating" every single time).
 */
object TripDetailSheetWriter {

    const val SHEET_NAME = "Dienstreise"

    /** 0-based row indices this sheet's formula/highlight block (only) needs to address —
     *  every other row (of the thirteen every trip writes, however sparse; only a value can be
     *  blank/`"—"`) is written strictly in sequence via the chained [SheetWriter.row] calls
     *  below, same convention as
     *  [de.littletrotter.infrastructure.export.workbook.InvoiceHeaderSheetWriter]'s own `Col`. */
    private object Row {
        const val CALCULATED_NET = 9
        const val FIXED_NET_PRICE = 10
        const val DELTA = 11
    }

    /** 0-based column index of the "Wert" column — the one column a Δ formula or
     *  [SheetWriter.markDeviationCell] ever targets on this sheet. */
    private const val VALUE_COLUMN = 1

    /** Excel is 1-based and row 1 is the header, so data row *n* (0-based, [Row]) sits at Excel
     *  row `n + 2` — [TripExpenseSheetWriter]'s own total row cross-references
     *  [CALCULATED_NET_EXCEL_ROW] by this same rule. */
    private const val EXCEL_ROW_OFFSET = 2

    /** The "Berechnete Summe (Netto)" field's own Excel row — [TripExpenseSheetWriter]'s total row
     *  control cell reads this cell directly (`'Dienstreise'!$VALUE_COLUMN_LETTER$…`) so the two
     *  sheets can never silently disagree about where that figure lives. */
    const val CALCULATED_NET_EXCEL_ROW: Int = Row.CALCULATED_NET + EXCEL_ROW_OFFSET

    /** The "Wert" column's own Excel letter — see [CALCULATED_NET_EXCEL_ROW]'s own KDoc. */
    val VALUE_COLUMN_LETTER: String = ExcelColumnLetters.of(VALUE_COLUMN)

    /** `internal`, not `private`: de.littletrotter.infrastructure.export.workbook.SheetWriterTest's
     *  own "no width below the FLAG floor" guard reads this directly. */
    internal val COLUMN_WIDTHS = listOf(ColumnWidths.NAME, ColumnWidths.TEXT)

    fun write(writer: WorkbookWriter, labels: TripDetailSheetLabels, view: TripWorkbookView): SheetWriter {
        val sheet = writer.sheet(SHEET_NAME)
        sheet.header(labels.columnHeaders, COLUMN_WIDTHS)

        sheet.row().text(labels.fieldTitle).text(view.title)
        sheet.row().text(labels.fieldPurpose).text(view.purpose)
        sheet.row().text(labels.fieldPeriodFrom).date(view.periodFrom)
        sheet.row().text(labels.fieldPeriodTo).date(view.periodTo)
        sheet.row().text(labels.fieldInvoiceNumber).text(view.invoiceNumber.ifBlank { null })
        sheet.row().text(labels.fieldInvoiceDate).date(view.invoiceDate)
        sheet.row().text(labels.fieldCustomer).text(view.customerName)
        sheet.row().text(labels.fieldCostCenter).text(view.costCenter)
        sheet.row().text(labels.fieldTaxTreatment).text(taxTreatmentText(labels, view))
        sheet.row().text(labels.fieldCalculatedNet).money(view.calculatedNet, view.currency)
        writeFixedPriceAndDelta(sheet, labels, view)
        sheet.row().text(labels.fieldReceiptsCount).integer(view.receiptsCount.toLong())

        return sheet
    }

    /**
     * The fixed price replaces the sum of expenses/per-diem days on the invoice, and the
     * Δ to the calculated sum is shown only here, never on the invoice itself. Without a fixed
     * price there is nothing to compare (the invoice already prints the calculated sum), so both
     * rows write [TripDetailSheetLabels.noValueLabel] and no formula/highlight is created at all.
     */
    private fun writeFixedPriceAndDelta(sheet: SheetWriter, labels: TripDetailSheetLabels, view: TripWorkbookView) {
        val fixed = view.fixedNetPrice
        if (fixed == null) {
            sheet.row().text(labels.fieldFixedNetPrice).text(labels.noValueLabel)
            sheet.row().text(labels.fieldDelta).text(labels.noValueLabel)
            return
        }
        sheet.row().text(labels.fieldFixedNetPrice).money(fixed, view.currency)
        val calculatedCell = "$VALUE_COLUMN_LETTER${Row.CALCULATED_NET + EXCEL_ROW_OFFSET}"
        val fixedCell = "$VALUE_COLUMN_LETTER${Row.FIXED_NET_PRICE + EXCEL_ROW_OFFSET}"
        sheet.row().text(labels.fieldDelta).formulaDeviation("$fixedCell-$calculatedCell")
        // Sheet row index (0-based): the header occupies index 0, so data row n (0-based within
        // Row) sits at sheet row index n + 1 — one less than its Excel row number above.
        sheet.markDeviationCell(Row.DELTA + 1, VALUE_COLUMN)
    }

    /**
     * [MessageFormat.format] applies [java.text.NumberFormat] (locale-dependent, e.g. "," as the
     * decimal separator) to a bare [java.math.BigDecimal]/[Number] argument — the same trap
     * [de.littletrotter.infrastructure.export.workbook.CellStyles]'s own KDoc calls out for a
     * pre-divided percentage. The rate is therefore rendered to a plain string first
     * ([java.math.BigDecimal.stripTrailingZeros]/[java.math.BigDecimal.toPlainString], never
     * scientific notation) so [MessageFormat] only ever substitutes text, locale-independent like
     * every other pattern this builder resolves.
     */
    private fun taxTreatmentText(labels: TripDetailSheetLabels, view: TripWorkbookView): String? {
        val rate = view.taxRatePercent ?: return null
        return MessageFormat.format(labels.taxTreatmentInheritsRatePattern, rate.stripTrailingZeros().toPlainString())
    }
}
