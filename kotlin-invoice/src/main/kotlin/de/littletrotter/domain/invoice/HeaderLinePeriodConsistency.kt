package de.littletrotter.domain.invoice

import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException

/**
 * Nekar rejects invoices whose header period (BT-73/BT-74) contradicts the line
 * periods (BT-134/BT-135): the header start must equal the earliest line start and the header
 * end must equal the latest line end ("darf nicht widersprechen").
 *
 * EN 16931 itself has no such rule — a header period and a line period may legitimately differ
 * under the norm (see [de.littletrotter.domain.compliance.PeriodConsistencyRules]'s own KDoc on
 * its `LT-PER-*` house rules, which only check *containment*, never exact equality). This is a
 * separate, stricter Nekar-specific house rule that only fires when **both** levels actually
 * carry period data — a header period without any line period, or line periods on an invoice
 * without a header period, are both legitimate on their own (per EN 16931 and per Nekar) and
 * produce no finding.
 *
 * Deliberately **not** part of [Invoice.validate] — that runs on every draft save
 * ([de.littletrotter.application.invoice.InvoiceLifecycleService.save]) and must stay permissive
 * while a user is still filling in line periods one at a time. And deliberately **not** added to
 * [de.littletrotter.domain.compliance.ComplianceRuleSets.DEFAULT] either: those findings are
 * gated behind the staged `littletrotter.compliance-rules.stage` rollout switch
 * ([de.littletrotter.application.invoice.FinalizationPolicy.enforceComplianceRules]) and default
 * to `OBSERVE` (log-only, never actually blocks) — which would silently defeat "blocks
 * finalization". Instead this mirrors the other *unconditional* structural pre-finalization
 * checks already wired into `→ SENT` ([ensureBuyerReferenceForFinalization],
 * [validateReverseChargeItemConsistency]): a real data-entry contradiction, checked once, right
 * before the transition, independent of any staged rollout.
 */
fun Invoice.validateHeaderLinePeriodConsistency() {
    val headerFrom = servicePeriodFrom
    val headerTo = servicePeriodTo
    if (headerFrom == null && headerTo == null) return

    val lineFroms = itemList.mapNotNull { it.servicePeriodFrom }
    val lineTos = itemList.mapNotNull { it.servicePeriodTo }
    if (lineFroms.isEmpty() && lineTos.isEmpty()) return

    val earliestLineStart = lineFroms.minOrNull()
    val latestLineEnd = lineTos.maxOrNull()

    val startMismatch = headerFrom != null && earliestLineStart != null && headerFrom != earliestLineStart
    val endMismatch = headerTo != null && latestLineEnd != null && headerTo != latestLineEnd
    if (!startMismatch && !endMismatch) return

    throw InvoiceValidationException(
        InvoiceValidationError.HEADER_LINE_PERIOD_MISMATCH,
        listOf(
            headerFrom?.toString() ?: "–",
            headerTo?.toString() ?: "–",
            earliestLineStart?.toString() ?: "–",
            latestLineEnd?.toString() ?: "–",
        ),
    )
}
