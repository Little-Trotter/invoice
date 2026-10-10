package de.littletrotter.domain.invoice

enum class InvoiceStatus(val labelKey: String, val cssClass: String) {
    DRAFT("invoice.status.draft", "draft"),
    IN_REVIEW("invoice.status.review", "review"),
    SENT("invoice.status.sent", "sent"),
    VOIDED("invoice.status.voided", "voided"),
    CANCELLED("invoice.status.cancelled", "cancelled");

    /** Returns true when transitioning from this status to [target] is allowed — delegates to
     *  [InvoiceStateMachine], the single table that decides (ST-1). */
    fun canTransitionTo(target: InvoiceStatus): Boolean = InvoiceStateMachine.isLegal(this, target)
}
