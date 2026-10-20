package de.littletrotter.domain.invoice

import java.time.LocalDate

/**
 * PO decision 2026-08-31 — § 14 Abs. 4 Nr. 6 UStG requires the invoice date (BT-2) to be a date
 * on or after the date the delivery/service was actually completed; a document dated BEFORE the
 * service it bills for is not a legal invoice.
 * [de.littletrotter.application.invoice.InvoiceLifecycleService.transition] already
 * overwrites [Invoice.invoiceDate] with "today" the moment an invoice moves to `ISSUED`
 * (single stamping point, see that method's own KDoc) — this guard runs right
 * after that stamp and blocks the transition while the resolved service end still lies strictly
 * AFTER it. Same-day is explicitly allowed (the PO's own examples): a single-day service on
 * 31.08. may be sent on 31.08., and a 20.08.–02.09. period may be sent from 02.09. onward.
 *
 * Deliberately mirrors [validateHeaderLinePeriodConsistency]/[ensureBuyerReferenceForFinalization]
 * rather than joining [de.littletrotter.domain.compliance.ComplianceRuleSets.DEFAULT]: those
 * findings only resolve to a `messageKey` the UI looks up later
 * ([de.littletrotter.domain.compliance.ComplianceFinding]'s own KDoc, "the domain never
 * produces text"), and [de.littletrotter.domain.compliance.InvoiceFinalizationBlockedException]'s own
 * message only ever names the rule id, never a rendered sentence — neither carries the literal
 * "§ 14 Abs. 4 Nr. 6 UStG" citation the PO wants IN the message the user sees. A plain, unconditional
 * guard with its own hand-written German exception text (the same shape as
 * [BuyerReferenceMissingException]) gets that text to the user unconditionally, independent of
 * any staged-rollout switch — appropriate here because, unlike the OBSERVE-staged compliance
 * rules, this is a hard statutory requirement with no "not yet enforced" phase the PO asked for.
 *
 * Resolves the service end via [resolveServiceEnd] — the same three BT sources
 * [de.littletrotter.domain.compliance.CoreCompletenessRules.SERVICE_PERIOD_XOR] already requires
 * SOME presence of, in the PO's own priority order (see that function's own KDoc). An invoice with
 * none of the three present has nothing for this guard to compare against — that same staged
 * compliance rule already owns "no service date at all", so this function simply no-ops rather
 * than duplicating the check. The predicate itself is [invoiceDateIsBeforeServiceEnd]; the
 * auto-correction and the due-date rules share both it and [resolveServiceEnd] with this guard
 * rather than each re-deriving "service end" independently.
 *
 * date invariants (PO 2026-09-02) — [Invoice.invoiceDate] is no longer nullable, so the former
 * "no invoiceDate at all" no-op branch this used to also guard against was removed as
 * unreachable; only the service-end side can still be absent.
 */
fun Invoice.ensureInvoiceDateNotBeforeServiceEnd() = ensureSendDateNotBeforeServiceEnd(invoiceDate)

/**
 * Same rule as [ensureInvoiceDateNotBeforeServiceEnd], but against [sendDate] — the date the
 * invoice WILL be stamped with on `-> ISSUED`. The stored draft [Invoice.invoiceDate] is irrelevant
 * (the transition stamps today), so a send-time pre-check must compare today, not the draft date.
 */
fun Invoice.ensureSendDateNotBeforeServiceEnd(sendDate: LocalDate) {
    val serviceEnd = resolveServiceEnd() ?: return
    if (invoiceDateIsBeforeServiceEnd(sendDate, serviceEnd)) {
        throw InvoiceDateBeforeServiceEndException(sendDate, serviceEnd)
    }
}

/**
 * Extends [RuntimeException] directly for the same reason as [BuyerReferenceMissingException] —
 * a sealed hierarchy in `domain/exception/` can't be subclassed from this package.
 */
class InvoiceDateBeforeServiceEndException(invoiceDate: LocalDate, serviceEnd: LocalDate) : RuntimeException(
    "Das Rechnungsdatum ($invoiceDate) darf nicht vor dem Ende des Leistungszeitraums bzw. dem " +
        "Lieferdatum ($serviceEnd) liegen — § 14 Abs. 4 Nr. 6 UStG verlangt ein Rechnungsdatum am " +
        "oder nach Abschluss der Lieferung/Leistung."
)
