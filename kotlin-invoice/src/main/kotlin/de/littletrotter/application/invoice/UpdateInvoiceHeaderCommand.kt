package de.littletrotter.application.invoice

import de.littletrotter.application.command.Command
import de.littletrotter.shared.Currency
import java.time.LocalDate

/**
 * Header fields the Erfassen (entry) pane edits, carried as an explicit intent instead of a
 * detached [de.littletrotter.domain.invoice.Invoice] the caller reconstructed from a read
 * model (arc-improvements AR1). [InvoiceLifecycleService.updateHeader] applies exactly these
 * fields onto the managed entity it loads itself — nothing this command doesn't name can change,
 * which is what makes it safe: `typeCode`, `validationStatus`, `originalInvoiceNumber`,
 * `documents`, `llmAnalyses` and every other field absent here are left untouched.
 */
data class UpdateInvoiceHeaderCommand(
    val invoiceId: Long,
    val expectedVersion: Long?,
    val invoiceDate: LocalDate?,
    val servicePeriodFrom: LocalDate?,
    val servicePeriodTo: LocalDate?,
    val dueDate: LocalDate?,
    val freeText: String?,
    val buyerReference: String?,
    val orderReference: String?,
    /** BT-11 — internal project reference, see ProjectNumberModule. */
    val projectNumber: String? = null,
    val orderReferenceDate: LocalDate?,
    val currency: Currency,
    val recipientId: Long?,
    val senderProfileId: Long?,
    val paymentTermId: Long?,
    /** ISO 3166-1 alpha-2 (e.g. `"LU"`) of the seller VAT registration this invoice
     *  is billed under, or `null` for the German default. */
    val sellerVatRegistrationCountry: String? = null,
    /** R-CB — `"GERMAN"` | `"INTERNATIONAL"` | `null` (defaults to `GERMAN`). Only
     *  takes effect while [sellerVatRegistrationCountry] resolves non-null OR the invoice's own
     *  [de.littletrotter.domain.invoice.Invoice.reverseCharge] decision is `true` — forced back
     *  to `GERMAN` otherwise (`InvoiceLifecycleService.updateHeader`, via
     *  [de.littletrotter.domain.invoice.isInvoiceFormatEditable]). */
    val invoiceFormat: String? = null,
    /** BT-13 — the buyer's purchase order reference
     *  ([de.littletrotter.domain.invoice.InvoiceReferences.purchaseOrderReference]). Defaulted so the
     *  addition stays additive for existing callers; the Erfassen pane always passes it. */
    val purchaseOrderReference: String? = null,
    /** The invoice's B2B profile override, applied only while [recipientId] does *not*
     *  change from the invoice's currently persisted recipient (`InvoiceLifecycleService
     *  .updateHeader` re-derives from the new recipient instead when it does — rule 1). `null`
     *  clears an override back to no profile, same nullable-field convention as every other field
     *  on this command. */
    val b2bProfileId: Long? = null,
    /** INV-B — explicit submission flag keeps legacy/internal callers from accidentally clearing
     * the persisted note just because they predate this field. Empty [invoiceNote] is a real,
     * manual value when this flag is true. */
    val invoiceNoteSubmitted: Boolean = false,
    val invoiceNote: String? = null,
    /** Explicit reset changes provenance back to SELLER_DEFAULT. */
    val resetInvoiceNoteToSellerDefault: Boolean = false,
    /** Same submission contract as [invoiceNoteSubmitted]/[invoiceNote] for the private-customer
     *  notice, so callers that predate it never clear the persisted copy. */
    val consumerNoteSubmitted: Boolean = false,
    val consumerNote: String? = null,
    val resetConsumerNoteToSellerDefault: Boolean = false,
    /** R6/Q5 — invoice-owned B2B overrides saved in the same transaction as this header. */
    val b2bFieldValues: Map<String, String?>? = null,
    /** INV-J — displayed effective values, including inherited profile/party defaults, used only
     * for the one-time free-text copy. */
    val effectiveB2bHeaderValues: Map<String, String?> = emptyMap(),
    /** INV-E-VAT/Q5 — explicit yes/no decision for the submitted party/VAT context. `null` is
     * unanswered and is rejected by the lifecycle rule whenever that context requires a choice. */
    val reverseChargeDecision: Boolean? = null,
    /** B3 — official codes of `FREE`/`HEADER` B2B term chips the office manager already
     *  clicked-to-append into [freeText] herself this save. Folded into the automatic silent merge
     *  ([InvoiceLifecycleService.mergeUnmappedB2bHeaderData]) as already-imported, this save and
     *  every later one, so the same value never gets appended a second time by that mechanism. */
    val manuallyImportedB2bCodes: Set<String> = emptySet(),
) : Command
