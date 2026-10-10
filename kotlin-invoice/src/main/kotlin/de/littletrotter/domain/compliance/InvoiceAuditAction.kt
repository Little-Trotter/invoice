package de.littletrotter.domain.compliance

/**
 * The business action the application was
 * performing when it wrote one or more `invoice_audit_journal` rows, as an auditor would name it
 * ("Statuswechsel", "Position hinzugefügt") rather than the row-level [InvoiceAuditEvent]
 * ("Rechnung geändert" for nearly everything). Persisted as `SMALLINT` = ordinal
 * (`invoice_audit_journal.action`, `V1__baseline_schema.sql` section 13,
 * `chk_invoice_audit_journal_action`) — same append-only, never-reorder, never-reuse contract as
 * [InvoiceAuditEvent].
 *
 * Set once per business transaction via
 * [de.littletrotter.application.shared.SetsAuditGuc]/[de.littletrotter.application.shared
 * .AuditGucInterceptor] (the `app.audit_action` GUC, outermost-wins — a nested call, e.g.
 * `InvoiceLifecycleService.addItem`'s own internal `recalculateTotals`, never overwrites the
 * outer action already set for that transaction). [UNSPECIFIED] is never set by application code
 * — the trigger's own default when the GUC is empty, i.e. a write that reached the audited tables
 * outside the application (see [InvoiceAuditJournalEntry.action]'s own KDoc).
 */
enum class InvoiceAuditAction {
    UNSPECIFIED,
    INVOICE_CREATED,
    HEADER_CHANGED,
    RECIPIENT_CHANGED,
    ITEM_ADDED,
    ITEM_CHANGED,
    ITEM_DELETED,
    RECALCULATED,
    STATUS_CHANGED,
    SEND_REQUESTED,
    DOCUMENT_GENERATED,
    VALIDATION_RECORDED,
    STORNO,
    RECEIPT_ASSIGNED,
    RECEIPT_DETACHED,
    TRIP_SAVED,
    REVERSE_CHARGE_DECIDED,
    INVOICE_DELETED,
    DOCUMENT_REGENERATED,
    DOCUMENT_RESTORED,
    DOCUMENT_REPRODUCED,
    DOCUMENT_REPRODUCTION_SEND_BLOCKED,
    CREATED_FROM_QUOTE,
    ;

    /** i18n key for this action's user-facing label — `journal.action.<NAME>`, same idiom as
     *  [InvoiceAuditEvent.labelKey]. */
    val labelKey: String get() = "journal.action.$name"

    companion object {
        /** Inverse of [Enum.ordinal] — for reading `invoice_audit_journal.action` back out of a
         *  native query row. Throws rather than silently defaulting: an out-of-range ordinal here
         *  means the Kotlin enum and the SQL CHECK/trigger have drifted apart, exactly the drift
         *  [InvoiceAuditEvent.fromOrdinal]'s own KDoc says must never happen. */
        fun fromOrdinal(ordinal: Int): InvoiceAuditAction =
            entries.getOrNull(ordinal)
                ?: throw IllegalArgumentException("Unknown invoice_audit_journal.action ordinal $ordinal")
    }
}
