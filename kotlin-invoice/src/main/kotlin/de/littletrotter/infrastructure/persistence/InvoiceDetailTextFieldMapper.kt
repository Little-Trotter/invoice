package de.littletrotter.infrastructure.persistence

import jakarta.persistence.Tuple
import java.time.LocalDate

/** The "texts" cohesive group: the header's own free-text and reference (BT block)
 *  columns. */
internal data class InvoiceDetailTextFields(
    val freeText: String? = null,
    /** INV-B — persisted invoice-owned note snapshot, independent from live sender master data. */
    val invoiceNote: String? = null,
    val invoiceNoteOrigin: String = "SELLER_DEFAULT",
    val consumerNote: String? = null,
    val consumerNoteOrigin: String = "SELLER_DEFAULT",
    val buyerReference: String? = null,
    /** BT-13 — neither [buyerReference] (BT-10) nor [orderReference] (BT-15). */
    val purchaseOrderReference: String? = null,
    val orderReference: String? = null,
    val orderReferenceDate: LocalDate? = null,
    /** BT-11 — the internal project reference (`invoice.project_number`). */
    val projectNumber: String? = null,
)

internal object InvoiceDetailTextFieldMapper {

    fun map(header: Tuple): InvoiceDetailTextFields = InvoiceDetailTextFields(
        freeText = InvoiceDetailRowSupport.parseString(header.get("freeText")),
        invoiceNote = InvoiceDetailRowSupport.parseString(header.get("invoiceNote")),
        // invoiceNoteOrigin is a non-nullable column on Invoice, so its fallback default
        // was unreachable for any persisted row.
        invoiceNoteOrigin = header.get("invoiceNoteOrigin").toString(),
        consumerNote = InvoiceDetailRowSupport.parseString(header.get("consumerNote")),
        consumerNoteOrigin = header.get("consumerNoteOrigin").toString(),
        buyerReference = InvoiceDetailRowSupport.parseString(header.get("buyerReference")),
        purchaseOrderReference = InvoiceDetailRowSupport.parseString(header.get("purchaseOrderReference")),
        orderReference = InvoiceDetailRowSupport.parseString(header.get("orderReference")),
        orderReferenceDate = header.get("orderReferenceDate") as LocalDate?,
        projectNumber = InvoiceDetailRowSupport.parseString(header.get("projectNumber")),
    )
}
