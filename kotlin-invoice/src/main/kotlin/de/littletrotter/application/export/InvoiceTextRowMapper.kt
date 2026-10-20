package de.littletrotter.application.export

import de.littletrotter.application.export.InvoiceHeaderRowIndex as Idx
import de.littletrotter.application.export.RowProjectionSupport.date
import de.littletrotter.application.export.RowProjectionSupport.long
import de.littletrotter.application.export.RowProjectionSupport.str
import java.time.OffsetDateTime

/**
 * The "texts" cohesive group of [InvoiceHeaderRowMapper.fromRow]'s 75-column projection:
 * the BT-block references, the cancellation reference, the free-text field and the audit columns.
 */
internal object InvoiceTextRowMapper {

    fun apply(target: InvoiceHeaderExportRow, row: Array<Any?>): InvoiceHeaderExportRow = target.copy(
        buyerReference = row.str(Idx.BUYER_REFERENCE),
        purchaseOrderReference = row.str(Idx.PURCHASE_ORDER_REFERENCE),
        orderReference = row.str(Idx.ORDER_REFERENCE),
        orderReferenceDate = row.date(Idx.ORDER_REFERENCE_DATE),
        offerReference = row.str(Idx.OFFER_REFERENCE),
        offerReferenceDate = row.date(Idx.OFFER_REFERENCE_DATE),
        projectNumber = row.str(Idx.PROJECT_NUMBER),
        costCenter = row.str(Idx.COST_CENTER),
        originalInvoiceNumber = row.str(Idx.ORIGINAL_INVOICE_NUMBER),
        originalInvoiceDate = row.date(Idx.ORIGINAL_INVOICE_DATE),
        createdAt = row.getOrNull(Idx.CREATED_AT) as? OffsetDateTime,
        createdBy = row.str(Idx.CREATED_BY),
        updatedAt = row.getOrNull(Idx.UPDATED_AT) as? OffsetDateTime,
        updatedBy = row.str(Idx.UPDATED_BY),
        version = row.long(Idx.VERSION),
        freeText = row.str(Idx.FREE_TEXT),
    )
}
