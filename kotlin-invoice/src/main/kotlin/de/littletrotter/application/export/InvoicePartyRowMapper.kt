package de.littletrotter.application.export

import de.littletrotter.application.export.InvoiceHeaderRowIndex as Idx
import de.littletrotter.application.export.RowProjectionSupport.long
import de.littletrotter.application.export.RowProjectionSupport.str

/**
 * The "parties" cohesive group of [InvoiceHeaderRowMapper.fromRow]'s 75-column
 * projection: seller/buyer identity, each resolved snapshot-vs-live the same way — the frozen
 * snapshot wins whenever its own presence-flag column (`sellerSnapshot.id`/`buyerSnapshot.id`) is
 * non-null — plus [InvoiceHeaderExportRow.partySource] itself.
 */
internal object InvoicePartyRowMapper {

    fun apply(target: InvoiceHeaderExportRow, row: Array<Any?>): InvoiceHeaderExportRow {
        val sellerFromSnapshot = row.long(Idx.SELLER_SNAPSHOT_ID) != null
        val buyerFromSnapshot = row.long(Idx.BUYER_SNAPSHOT_ID) != null
        return target.copy(
            sellerName = row.field(sellerFromSnapshot, Idx.SELLER_SNAPSHOT_NAME, Idx.SELLER_NAME_LIVE),
            sellerVatId = row.field(sellerFromSnapshot, Idx.SELLER_SNAPSHOT_VAT_ID, Idx.SELLER_VAT_ID_LIVE),
            sellerTaxNumber = row.field(sellerFromSnapshot, Idx.SELLER_SNAPSHOT_TAX_NUMBER, Idx.SELLER_TAX_NUMBER_LIVE),
            sellerIdentifierValue = row.field(sellerFromSnapshot, Idx.SELLER_SNAPSHOT_ID_VALUE, Idx.SELLER_IDENTIFIER_VALUE_LIVE),
            sellerIdentifierSchemeId = row.field(sellerFromSnapshot, Idx.SELLER_SNAPSHOT_ID_SCHEME, Idx.SELLER_IDENTIFIER_SCHEME_LIVE),
            sellerRegisterNumber = row.field(sellerFromSnapshot, Idx.SELLER_SNAPSHOT_REG_VALUE, Idx.SELLER_REGISTER_NUMBER_LIVE),
            sellerRegisterSchemeId = row.field(sellerFromSnapshot, Idx.SELLER_SNAPSHOT_REG_SCHEME, Idx.SELLER_REGISTER_SCHEME_LIVE),
            sellerElectronicAddressValue = row.field(sellerFromSnapshot, Idx.SELLER_SNAPSHOT_EA_VALUE, Idx.SELLER_EA_VALUE_LIVE),
            sellerElectronicAddressSchemeId = row.field(sellerFromSnapshot, Idx.SELLER_SNAPSHOT_EA_SCHEME, Idx.SELLER_EA_SCHEME_LIVE),
            buyerName = row.field(buyerFromSnapshot, Idx.BUYER_SNAPSHOT_NAME, Idx.BUYER_NAME_LIVE),
            buyerVatId = row.field(buyerFromSnapshot, Idx.BUYER_SNAPSHOT_VAT_ID, Idx.BUYER_VAT_ID_LIVE),
            buyerIdentifierValue = row.field(buyerFromSnapshot, Idx.BUYER_SNAPSHOT_ID_VALUE, Idx.BUYER_IDENTIFIER_VALUE_LIVE),
            buyerIdentifierSchemeId = row.field(buyerFromSnapshot, Idx.BUYER_SNAPSHOT_ID_SCHEME, Idx.BUYER_IDENTIFIER_SCHEME_LIVE),
            buyerElectronicAddressValue = row.field(buyerFromSnapshot, Idx.BUYER_SNAPSHOT_EA_VALUE, Idx.BUYER_EA_VALUE_LIVE),
            buyerElectronicAddressSchemeId = row.field(buyerFromSnapshot, Idx.BUYER_SNAPSHOT_EA_SCHEME, Idx.BUYER_EA_SCHEME_LIVE),
            partySource = if (buyerFromSnapshot) PartySource.SNAPSHOT else PartySource.MASTER_DATA,
        )
    }

    /** Picks [snapshotIndex] when [fromSnapshot], [liveIndex] otherwise — the one shared shape
     *  behind every seller/buyer column's snapshot-vs-live fallback, so [apply] itself stays a flat
     *  sequence of field assignments instead of repeating the same `if` fourteen times. */
    private fun Array<Any?>.field(fromSnapshot: Boolean, snapshotIndex: Int, liveIndex: Int): String? =
        str(if (fromSnapshot) snapshotIndex else liveIndex)
}
