package de.littletrotter.ui.view.journal

import de.littletrotter.domain.compliance.InvoiceAuditAction

/**
 * Maps [InvoiceAuditAction] (the business action a journal line groups its technical rows
 * under, see [de.littletrotter.application.dto.InvoiceAuditActionLine]) to what the grouped
 * grid shows: an i18n key (`journal.action.<NAME>`, delegating to [InvoiceAuditAction.labelKey]
 * itself — the UI, the Excel export and a future auditor tool must all say the same word for the
 * same action) and a `.status-chip`/`.journal-tile` colour modifier. Pure and Vaadin-free, same
 * shape as [JournalEventLabels] it sits next to.
 *
 * Colour reuses the three existing tones [JournalEventLabels] already established
 * (`success`/`warning`/`cancelled`) — no new colour is introduced (PO decision 4: "no other row is
 * coloured for emphasis"; the tone here only decorates the tile/Vorgang text, it is never the row's
 * only carrier of meaning, and [isExternalWrite] alone drives the one row-level warning treatment
 * the spec asks for).
 */
object JournalActionLabels {

    fun labelKey(action: InvoiceAuditAction): String = action.labelKey

    /** B3 — the specific "Direkter Datenbankeingriff"/"Direct database change" label the Vorgang
     *  column shows instead of [labelKey]'s generic `journal.action.UNSPECIFIED` whenever
     *  [isExternalWrite] is true for the line (a `journal.action.UNSPECIFIED` row that is NOT also a
     *  DB-role write — a case the enum's own KDoc says never happens in practice, but the label set
     *  still needs a value for it — keeps the plain, generic wording). */
    const val EXTERNAL_WRITE_LABEL_KEY: String = "journal.action.UNSPECIFIED.external"

    /** [InvoiceAuditAction.UNSPECIFIED] together with a DB-role actor (see
     *  [JournalActorClassifier.isDatabaseRole]) is a write that reached the audited tables outside
     *  the application — the one action this package renders in the warning tone at the ROW level
     *  ("Direkter Datenbankeingriff"). [action] alone decides this predicate; the actor check is
     *  the caller's job (`JournalActorClassifier`), kept separate so this object stays a pure
     *  action → label/colour mapping. */
    fun isExternalWrite(action: InvoiceAuditAction): Boolean = action == InvoiceAuditAction.UNSPECIFIED

    /** `.status-chip--*`/`.journal-tile--*` modifier for [action] — creation/assignment-shaped
     *  actions read as `success`, a deletion/detach/cancellation-shaped action as `cancelled`,
     *  everything else (a change to something that already existed) as `warning`. */
    fun cssModifier(action: InvoiceAuditAction): String = when (action) {
        InvoiceAuditAction.INVOICE_CREATED,
        InvoiceAuditAction.CREATED_FROM_QUOTE,
        InvoiceAuditAction.ITEM_ADDED,
        InvoiceAuditAction.RECEIPT_ASSIGNED,
        InvoiceAuditAction.DOCUMENT_GENERATED,
        InvoiceAuditAction.SEND_REQUESTED,
        InvoiceAuditAction.TRIP_SAVED,
        InvoiceAuditAction.DOCUMENT_REGENERATED,
        InvoiceAuditAction.DOCUMENT_RESTORED,
        InvoiceAuditAction.DOCUMENT_REPRODUCED,
        -> "success"

        InvoiceAuditAction.ITEM_DELETED,
        InvoiceAuditAction.RECEIPT_DETACHED,
        InvoiceAuditAction.CANCELLATION,
        InvoiceAuditAction.INVOICE_DELETED,
        InvoiceAuditAction.DOCUMENT_REPRODUCTION_SEND_BLOCKED,
        -> "cancelled"

        else -> "warning"
    }
}
