package de.littletrotter.application.export

import de.littletrotter.application.export.InvoiceHeaderRowIndex as Idx
import de.littletrotter.application.export.RowProjectionSupport.bool
import de.littletrotter.shared.country.Country

/**
 * The "tax" cohesive group of [InvoiceHeaderRowMapper.fromRow]'s 75-column projection:
 * the seller's own additional VAT registration country and the reverse-charge flag.
 */
internal object InvoiceTaxRowMapper {

    fun apply(target: InvoiceHeaderExportRow, row: Array<Any?>): InvoiceHeaderExportRow = target.copy(
        sellerVatRegistrationCountry = (row.getOrNull(Idx.SELLER_VAT_REGISTRATION_COUNTRY) as? Country)?.alpha2,
        reverseCharge = row.bool(Idx.REVERSE_CHARGE) ?: false,
    )
}
