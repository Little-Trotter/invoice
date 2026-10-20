package de.littletrotter.domain.invoice

import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.value.DiscountPercent
import jakarta.persistence.Embeddable

/**
 * W7 — like the P2 party snapshots ([InvoicePartySnapshot]), freezes the payment-term values this
 * invoice was finalized with, so a later edit to the master-data [PaymentTerm] row (name, discount
 * terms, …) never leaks into an already-`ISSUED` document. Set exactly once, at the transition
 * to `ISSUED` ([de.littletrotter.application.invoice.InvoiceLifecycleService.transition], next
 * to the invoice-number assignment) via [Invoice.freezePaymentTermSnapshot] — never re-derived afterward,
 * the same rule as every other compliance-relevant snapshot on this aggregate.
 *
 * Unlike [InvoicePartySnapshot] this is a single `@Embedded` value object directly on [Invoice],
 * not a separate entity/table: there is exactly one payment term per invoice (not one row per
 * [PartyRole]), so P1's reason for a side table (reusable FK target, one row per role) does not
 * apply here.
 *
 * `null` on [Invoice.paymentTermSnapshot] means either a draft (not yet finalized) or an invoice
 * finalized before this snapshot existed; both read the live [Invoice.paymentTerm] association as
 * a fallback (same pattern as [InvoicePartySnapshot]'s own "`null` only for pre-P2" note).
 *
 * A plain class with body-level `var`s, not a constructor-parameter `data class` (predates the
 * `kotlin-noarg` plugin addition; kept as-is here for a minimal diff, not because it still needs to be).
 */
@Embeddable
class InvoicePaymentTermSnapshot {
    /**
     * The text **as printed**: the payment term's name
     * already resolved in the invoice's own [Invoice.documentLanguage] at the moment this
     * snapshot was frozen, not the raw German master-data value. `document_language` is itself
     * frozen at invoice creation, so this resolution is deterministic and never re-derived.
     * Replaces the former `name`/`englishName` pair — there is exactly one printed name, in
     * exactly one language, per invoice.
     */
    var name: String = ""
        private set
    var dueInDays: Int = 0
        private set
    var discountPercent: DiscountPercent = DiscountPercent.ZERO
        private set
    var discountDays: Int? = null
        private set

    companion object {
        /** Freezes [term]'s document-relevant values, resolving [PaymentTerm.nameFor] against
         *  [documentLanguage] so the frozen [name] is the text this exact document showed, in this
         *  exact document's language. */
        fun of(term: PaymentTerm, documentLanguage: String): InvoicePaymentTermSnapshot = InvoicePaymentTermSnapshot().apply {
            name = term.nameFor(documentLanguage) ?: ""
            dueInDays = term.dueInDays
            discountPercent = term.discountPercent
            discountDays = term.discountDays
        }
    }
}
