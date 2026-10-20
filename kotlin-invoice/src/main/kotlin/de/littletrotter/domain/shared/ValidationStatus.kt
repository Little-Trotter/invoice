package de.littletrotter.domain.shared

/** Lifecycle of XRechnung/ZUGFeRD validation for an invoice or document.
 *
 * validation-status-truthful-pending.md — [NOT_VALIDATED] and [PENDING] used to be conflated
 * (every new invoice was born [PENDING], which doubled as "never validated"). They are now
 * distinct: [NOT_VALIDATED] is the true default (no run has ever started), [PENDING] is only set
 * while a generation job is actually in flight ([de.littletrotter.application.invoice
 * .DocumentJobPublisher.publish]) and cleared to [UNAVAILABLE] if that job fails
 * ([de.littletrotter.application.invoice.GenerationJobService.markFailed]).
 *
 * [ValidationStatusConverter][de.littletrotter.domain.shared.converters.ValidationStatusConverter]
 * persists these by ordinal — [NOT_VALIDATED] is appended last (ordinal 4) so the existing 0..3
 * mapping of the other four values never shifts. */
enum class ValidationStatus {
    PENDING,
    VALID,
    INVALID,
    UNAVAILABLE,
    NOT_VALIDATED;

    /**
     * Same pattern as [de.littletrotter.domain.invoice
     * .GenerationJobStateMachine] — whether transitioning from this status to [target] is a
     * legal edge. Every edge below is one that [de.littletrotter.domain.invoice.Invoice
     * .validationStatus] is actually moved along in production, not an invented possibility:
     *
     * ```
     * NOT_VALIDATED                    -> PENDING       (DocumentJobPublisher.publish — first ever run)
     * VALID | INVALID | UNAVAILABLE    -> PENDING       (DocumentJobPublisher.publish — re-print/re-validate)
     * PENDING                          -> PENDING       (DocumentJobPublisher.publish while an older,
     *                                                     non-latest job for the same invoice is still in
     *                                                     flight — see below)
     * PENDING                          -> VALID | INVALID | UNAVAILABLE
     *                                                    (DocumentGeneratedReactionHandler
     *                                                     .onDocumentGeneratedUpdateValidationStatus, only
     *                                                     once no non-terminal job remains for the invoice —
     *                                                     resolved from the newest COMPLETED job with a
     *                                                     decodable KoSIT report, or UNAVAILABLE if none has
     *                                                     one, so two jobs racing to complete can never leave
     *                                                     the invoice stuck PENDING;
     *                                                     GenerationJobService.clearPendingValidationStatus
     *                                                     on job failure)
     * ```
     * `PENDING -> PENDING` is deliberately a legal self-edge here, unlike
     * [de.littletrotter.domain.invoice.GenerationJobStateMachine]'s table: this status is a
     * reassertable "an e-invoice run is in flight" flag, not a single job's own one-shot
     * lifecycle — [de.littletrotter.application.invoice.DocumentJobPublishTransaction.publishTransactional]
     * writes PENDING unconditionally on every publish, with no read of the prior value, and
     * nothing else in the codebase enforces "only one job in flight per invoice" that would rule
     * out a second publish landing while the first is still running. [NOT_VALIDATED] is never a
     * transition target — it is only ever the field's construction-time default.
     */
    fun canTransitionTo(target: ValidationStatus): Boolean = when (this) {
        NOT_VALIDATED -> target == PENDING
        PENDING -> target == PENDING || target == VALID || target == INVALID || target == UNAVAILABLE
        VALID, INVALID, UNAVAILABLE -> target == PENDING
    }
}
