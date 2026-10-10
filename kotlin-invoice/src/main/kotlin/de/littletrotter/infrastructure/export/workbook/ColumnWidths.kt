package de.littletrotter.infrastructure.export.workbook

/**
 * The column-width classes the design spec defines, in whole characters (POI's own unit
 * is 1/256th of a character; [SheetWriter.header] and [SheetWriter.columnWidths] do that
 * conversion, so every sheet writer only ever names one of these constants). Every sheet writer
 * declares one class per column, right next to its own column catalogue (`Col` object), as its own
 * `COLUMN_WIDTHS` list — [SheetWriter.header]'s `require(columnWidths.size == labels.size)` then
 * catches a column added to one list but forgotten in the other.
 *
 * The reviewer's own rule of thumb: a [MONEY] column must display
 * `-999.999.999,99 EUR` without `#####`, and a [CODE] column the longest invoice number datagen
 * produces (`DSC001-2024-01-00001`, 20 characters) plus the autofilter arrow.
 */
internal object ColumnWidths {

    /** Booleans, ordinal codes, tax-category letters. */
    const val FLAG = 8

    /** A tax rate, written as the plain number it is (`19`, never `%`-formatted). */
    const val PERCENT = 10

    /** Dates. */
    const val DATE = 12

    /** Quantities, and the "Δ item counts" control column. */
    const val QUANTITY = 14

    /** Every money and Δ-money column, and every money-formatted control formula. */
    const val MONEY = 16

    /** Database ids, position ids, and plain counts. */
    const val ID = 16

    /** Timestamps. */
    const val DATE_TIME = 20

    /** Invoice number, customer number, VAT ids, IBAN, BIC, references. */
    const val CODE = 22

    /** Names, street, city, e-mail, article names, units. */
    const val NAME = 32

    /** Free text, notes, descriptions, journal details — wrapped. */
    const val TEXT = 60

    /** The "Export" cover sheet's own field-label column. */
    const val EXPORT_FIELD = 40

    /** The "Export" cover sheet's own value/notes column — wrapped. */
    const val EXPORT_VALUE = 110
}
