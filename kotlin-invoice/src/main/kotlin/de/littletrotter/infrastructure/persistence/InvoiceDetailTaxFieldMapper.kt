package de.littletrotter.infrastructure.persistence

import de.littletrotter.shared.country.Country
import jakarta.persistence.Tuple

/**
 * The "tax" cohesive group: the reverse-charge flag, the frozen payment-term name (W7 —
 * `COALESCE` prefers the frozen snapshot over the live association's resolved name for an
 * already-SENT invoice), the seller's own additional VAT
 * registration country and the invoice's live `PaymentTerm` association id.
 */
internal data class InvoiceDetailTaxFields(
    val reverseCharge: Boolean = false,
    val paymentTermName: String? = null,
    val sellerVatRegistrationCountry: String? = null,
    val paymentTermId: Long? = null,
)

internal object InvoiceDetailTaxFieldMapper {

    fun map(header: Tuple): InvoiceDetailTaxFields = InvoiceDetailTaxFields(
        // reverseCharge is a non-nullable Boolean column on Invoice, so its fallback
        // default was unreachable for any persisted row.
        reverseCharge = header.get("reverseCharge") as Boolean,
        paymentTermName = InvoiceDetailRowSupport.parseString(header.get("paymentTermName")),
        // The column itself is nullable (kept), but once present it is always a Country
        // instance with a non-null alpha2 — the inner `as?`/`?:` fallback was unreachable.
        sellerVatRegistrationCountry = (header.get("sellerVatRegistrationCountry") as? Country)?.alpha2,
        paymentTermId = (header.get("paymentTermId") as Number?)?.toLong(),
    )
}
