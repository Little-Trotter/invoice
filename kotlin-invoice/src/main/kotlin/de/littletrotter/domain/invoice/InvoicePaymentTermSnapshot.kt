package de.littletrotter.domain.invoice

import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.value.DiscountPercent
import jakarta.persistence.Embeddable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * W7 — like the P2 party snapshots ([InvoicePartySnapshot]), freezes the payment-term values this
 * invoice was finalized with, so a later edit to the master-data [PaymentTerm] row (name, discount
 * terms, …) never leaks into an already-`SENT` document. Set exactly once, at the transition
 * to `SENT` ([de.littletrotter.application.invoice.InvoiceLifecycleService.transition], next
 * to the invoice-number assignment) via [freezePaymentTermSnapshot] — never re-derived afterward,
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
    var dueInDays: Int = 0
    var discountPercent: DiscountPercent = DiscountPercent.ZERO
    var discountDays: Int? = null
}

/** Freezes this payment term's document-relevant values onto an invoice snapshot,
 *  resolving [PaymentTerm.nameFor] against [documentLanguage] so the frozen [InvoicePaymentTermSnapshot.name]
 *  is the text this exact document showed, in this exact document's language. */
fun PaymentTerm.snapshotPaymentTerm(documentLanguage: String): InvoicePaymentTermSnapshot = InvoicePaymentTermSnapshot().apply {
    name = this@snapshotPaymentTerm.nameFor(documentLanguage) ?: ""
    dueInDays = this@snapshotPaymentTerm.dueInDays
    discountPercent = this@snapshotPaymentTerm.discountPercent
    discountDays = this@snapshotPaymentTerm.discountDays
}

/**
 * W7 — freezes [Invoice.paymentTerm] onto [Invoice.paymentTermSnapshot], called once at the
 * `SENT` transition. A no-op when [Invoice.paymentTerm] is `null` (no payment term chosen at
 * all — nothing to freeze, readers fall back to [Invoice.paymentTermsDays] as before) and a no-op
 * when a snapshot already exists (an already-`SENT` invoice never re-enters `transition`'s
 * `SENT` branch, RV-02, but this keeps the function itself idempotent regardless).
 */
fun Invoice.freezePaymentTermSnapshot() {
    if (paymentTermSnapshot != null) return
    val term = paymentTerm ?: return
    paymentTermSnapshot = term.snapshotPaymentTerm(documentLanguage)
}

/**
 * finding-fixes (2026-08-31, PO decision) — keeps [Invoice.dueDate] consistent with the
 * `invoiceDate` stamp [de.littletrotter.application.invoice.InvoiceLifecycleService.transition]
 * applies at the `→ SENT` transition (overwriting whatever the draft carried with "today"). Left
 * untouched, a draft's dueDate — computed against its OLD invoiceDate, potentially months earlier
 * — can end up BEFORE the just-stamped invoiceDate. That is no longer rejected (the due date is a
 * plausibility rule, not a statutory one), but it is still wrong for any invoice whose draft
 * predates its actual send day, and it is what `NumberJournalReportServiceIT` reproduced.
 *
 * Called right after [freezePaymentTermSnapshot] so [Invoice.paymentTermSnapshot] already reflects
 * whatever [Invoice.paymentTerm] this invoice carries, if any. Three cases, in this order:
 *
 * 1. [previousDueDate] is `null` — this invoice never had a due date at all; stays `null`, nothing
 *    to keep consistent.
 * 2. [Invoice.paymentTermSnapshot] carries a `dueInDays` — the exact day-count the entry pane's own
 *    `InvoiceDetailViewOverview.computeDueDate` uses to derive dueDate from a payment-term
 *    selection, so re-deriving from it here reproduces what the pane would show for "today".
 *    [Invoice.paymentTermsDays] is deliberately NOT used as a fallback source here despite the
 *    similar name: it is a `create()`-time-only default (14) that is never written again once a
 *    payment term is actually chosen (verified — no other call site assigns it), so it would
 *    silently disagree with the payment term this document actually carries.
 * 3. No payment term was ever chosen (no snapshot) — the only fact this invoice still carries about
 *    its own due-date policy is the day-gap the draft itself had between [previousDueDate] and
 *    [previousInvoiceDate]; that gap is preserved onto the new invoiceDate. If [previousInvoiceDate]
 *    itself was never set either, [previousDueDate] is kept as-is rather than an arbitrary guess.
 *
 * date invariants (PO 2026-09-02) — [Invoice.invoiceDate] is no longer nullable, so the "this
 * invoice never had an invoiceDate at all" edge case removed here can no longer occur through the
 * domain layer; [previousInvoiceDate] (a caller-captured snapshot of a *prior* call, not this
 * field) stays independently nullable and is still handled by case 3 above.
 */
fun Invoice.recomputeDueDateForSend(previousInvoiceDate: LocalDate?, previousDueDate: LocalDate?) {
    if (previousDueDate == null) return
    val newInvoiceDate = invoiceDate
    val dueInDays = paymentTermSnapshot?.dueInDays
    dueDate = when {
        dueInDays != null -> newInvoiceDate.plusDays(dueInDays.toLong())
        previousInvoiceDate != null -> newInvoiceDate.plusDays(ChronoUnit.DAYS.between(previousInvoiceDate, previousDueDate))
        else -> previousDueDate
    }
}
