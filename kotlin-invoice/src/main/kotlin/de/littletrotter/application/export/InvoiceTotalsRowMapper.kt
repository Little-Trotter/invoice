package de.littletrotter.application.export

import de.littletrotter.application.export.InvoiceHeaderRowIndex as Idx
import de.littletrotter.application.export.RowProjectionSupport.bool
import de.littletrotter.application.export.RowProjectionSupport.enumOrDefault
import de.littletrotter.application.export.RowProjectionSupport.int
import de.littletrotter.application.export.RowProjectionSupport.money
import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money

/**
 * The "totals" cohesive group of [InvoiceHeaderRowMapper.fromRow]'s 75-column projection:
 * currency, the header's own net/vat/gross amounts, its item/receipt counts and the
 * needs-recalculation flag.
 */
internal object InvoiceTotalsRowMapper {

    fun apply(target: InvoiceHeaderExportRow, row: Array<Any?>): InvoiceHeaderExportRow = target.copy(
        currency = row.enumOrDefault(Idx.CURRENCY, Currency.EUR),
        netAmount = row.money(Idx.NET_AMOUNT) ?: Money.ZERO,
        vatAmount = row.money(Idx.VAT_AMOUNT) ?: Money.ZERO,
        grossAmount = row.money(Idx.GROSS_AMOUNT) ?: Money.ZERO,
        itemsCount = row.int(Idx.ITEMS_COUNT) ?: 0,
        receiptsCount = row.int(Idx.RECEIPTS_COUNT) ?: 0,
        needsRecalculation = row.bool(Idx.NEEDS_RECALCULATION) ?: false,
    )
}
