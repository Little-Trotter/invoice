package de.littletrotter.domain.invoice

import java.time.LocalDate

/**
 * PO decision 2026-09-01 () — pure date logic for rules 1-4 of
 * the invoice-date/service-period/due-date decision. Lives beside `InvoiceDateSendGuard` so the
 * send gate (rule 1) and these functions share exactly one definition of "service end"; none of
 * these functions load an entity graph, open a session, or touch the UI — every one is a plain
 * function over dates (or over [Invoice]'s own already-loaded fields) so the case table in the
 * spec can be pinned 1:1 as unit tests.
 *
 * Rule numbering follows the spec:
 * 1. [invoiceDateIsBeforeServiceEnd] — the ERROR predicate [ensureInvoiceDateNotBeforeServiceEnd]
 *    (in `InvoiceDateSendGuard`) already enforces at the send gate.
 * 2. [correctedInvoiceDate] — the auto-correction that moves an invoice date forward to the
 *    service end, never backwards.
 * 3. [DueDateWarning]/[dueDateWarning] — the non-blocking due-date check, surfaced as a
 *    [de.littletrotter.domain.compliance.ComplianceFinding] by
 *    [de.littletrotter.domain.compliance.DueDateRules].
 * 4. [derivedDueDate] — the due date recomputed from the invoice date and the payment term.
 */

/**
 * [Invoice.resolveServiceEnd] — the one service-end resolution every rule here (and the send
 * gate) uses, in the PO's own priority order: BT-72 (delivery date) first, then the header period
 * end BT-74, and only when neither header source is set, the LATEST line-level period end
 * (BT-135, or BT-134 on a line that only carries a single point in time). `null` when none of the
 * three is present — [de.littletrotter.domain.compliance.CoreCompletenessRules.SERVICE_PERIOD_XOR]
 * owns "no service date at all", not these rules.
 *
 * Extracted from `InvoiceDateSendGuard`'s former private `resolveServiceEndForSendGate` with no
 * behavior change, so it has exactly one definition, reusable and testable outside the send gate.
 */
fun Invoice.resolveServiceEnd(): LocalDate? {
    deliveryDate?.let { return it }
    servicePeriodTo?.let { return it }
    return itemList.mapNotNull { it.servicePeriodTo ?: it.servicePeriodFrom }.maxOrNull()
}

/**
 * Rule 1 predicate — true only when both [invoiceDate] and [serviceEnd] are present and
 * [invoiceDate] lies strictly before [serviceEnd]. Same day is allowed (the PO's own examples);
 * a `null` on either side never blocks — that is a different rule's job
 * ([de.littletrotter.domain.compliance.CoreCompletenessRules.SERVICE_PERIOD_XOR] for a missing
 * service date, the caller's own null-check for a missing invoice date).
 */
fun invoiceDateIsBeforeServiceEnd(invoiceDate: LocalDate?, serviceEnd: LocalDate?): Boolean =
    invoiceDate != null && serviceEnd != null && invoiceDate.isBefore(serviceEnd)

/**
 * Rule 2 — the auto-correction that spares the user the arithmetic: an [invoiceDate] that lies
 * before [serviceEnd] is moved forward to it. Returns [invoiceDate] unchanged in every other
 * case, including both `null` — the correction only ever moves the date **forward**, never
 * backwards, and never invents a date that was not there to begin with.
 */
fun correctedInvoiceDate(invoiceDate: LocalDate?, serviceEnd: LocalDate?): LocalDate? =
    if (invoiceDateIsBeforeServiceEnd(invoiceDate, serviceEnd)) serviceEnd else invoiceDate

/**
 * Rule 3 result — WHICH condition fired, not just whether one did, so a caller (the UI) can
 * explain why the due date is flagged instead of showing a single generic message.
 * [BEFORE_INVOICE_DATE] takes priority over [BEFORE_SERVICE_END] when both would technically
 * apply, mirroring the order the spec itself lists them in ("... before the invoice date, the
 * service day, or the service end").
 */
enum class DueDateWarning {
    /** Nothing to flag: no due date set, or it lies on/after both the invoice date and the
     *  service end. */
    NONE,

    /** The due date lies before the invoice date. */
    BEFORE_INVOICE_DATE,

    /** The due date lies before the resolved service end (and not before the invoice date —
     *  that case is [BEFORE_INVOICE_DATE]). */
    BEFORE_SERVICE_END,
}

/**
 * Rule 3 — never blocking (see [DueDateWarning]'s own KDoc for why: the due date carries no
 * statutory weight, unlike the invoice date itself). Same-day is explicitly fine on both
 * comparisons, matching rule 1's own same-day allowance.
 */
fun dueDateWarning(invoiceDate: LocalDate?, serviceEnd: LocalDate?, dueDate: LocalDate?): DueDateWarning = when {
    dueDate == null -> DueDateWarning.NONE
    invoiceDate != null && dueDate.isBefore(invoiceDate) -> DueDateWarning.BEFORE_INVOICE_DATE
    serviceEnd != null && dueDate.isBefore(serviceEnd) -> DueDateWarning.BEFORE_SERVICE_END
    else -> DueDateWarning.NONE
}

/**
 * Rule 4 — the due date derived from [invoiceDate] and [paymentTermDays]. Returns `null` when
 * [invoiceDate] itself is `null`: there is nothing to derive from, and the caller must leave
 * whatever due date already exists untouched rather than overwrite it with `null` (case 22 of the
 * spec's case table).
 */
fun derivedDueDate(invoiceDate: LocalDate?, paymentTermDays: Int): LocalDate? =
    invoiceDate?.plusDays(paymentTermDays.toLong())
