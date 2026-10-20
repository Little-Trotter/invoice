package de.littletrotter.application.invoice.port.outbound

import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.shared.country.Country

/** Point lookups and existence checks over invoices, read only. */
interface InvoiceLookupRepository {
    fun findByRecipientId(recipientId: Long, pageIndex: Int = 0, pageSize: Int = 60): List<Invoice>

    fun findBySenderProfileId(senderProfileId: Long, pageIndex: Int = 0, pageSize: Int = 60): List<Invoice>

    /**
     * Count of NON-finalized (`DRAFT`/`IN_REVIEW`) invoices of [senderProfileId] whose
     * [Invoice.sellerVatRegistrationCountry] is [country]. The removal guard of an
     * `additionalVatRegistration` uses this to block removing a country an open invoice still
     * references; an `ISSUED`/`CANCELLED` invoice carries its own frozen snapshot and is
     * unaffected by the removal, so those never count here.
     */
    fun countOpenInvoicesForSellerVatCountry(senderProfileId: Long, country: Country): Long

    fun existsByBillingAddressId(addressId: Long): Boolean

    fun existsByDeliveryAddressId(addressId: Long): Boolean

    /**
     * Whether any invoice references [b2bProfileId] through its own manual override
     * ([Invoice.b2bProfile], per-invoice pin), independent of any recipient's standing default.
     * The profile deletion guard uses it so a still-referenced profile is rejected with a readable
     * error instead of the raw foreign key violation at flush time.
     */
    fun existsByB2bProfileId(b2bProfileId: Long): Boolean

    /** Loads the [Invoice] aggregate that owns the item with [itemId], with its items populated.
     *  A position is reached only through its aggregate, never fetched standalone. */
    fun findInvoiceByItemId(itemId: Long): Invoice?

    /**
     * Position numbers ("Pos. 3") for many invoice items in ONE query, keyed by item id. The
     * receipt journal sentence for an item scope needs this: `invoice_item_receipt`/`trip_expense`
     * carry only the item's database key, never the auditor-facing position number. An item id
     * with no matching invoice (a hard-deleted DRAFT position) is simply missing from the result.
     */
    fun findItemPositions(itemIds: Collection<Long>): Map<Long, String>

    /** All invoices whose [Invoice.originalInvoiceNumber] points to [number] (existing cancellations). */
    fun findByOriginalInvoiceNumber(number: String): List<Invoice>
}
