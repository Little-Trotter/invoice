package de.littletrotter.ui.view.journal

import de.littletrotter.domain.compliance.InvoiceAuditEvent
import de.littletrotter.ui.component.ChipTone

/**
 * Maps [InvoiceAuditEvent] (the SMALLINT enum `invoice_audit_journal.event` carries, see
 * that class's own KDoc) to what the journal views show: an i18n key (never a raw ordinal)
 * and a [ChipTone] for the chip. Pure and Vaadin-free so the mapping is unit-testable without a UI.
 *
 * CREATED reads as a positive addition (ok), UPDATED as a change worth noticing (warn), DELETED as
 * the more consequential, terminal-for-that-row action (storno). One tone per verb, independent
 * of which of the captured tables (invoice/invoice_item/invoice_document) the event belongs to.
 */
object JournalEventLabels {

    /** i18n key for [event]'s label — every one of the 12 [InvoiceAuditEvent] entries has both a
     *  `journal.event.<NAME>` key in `messages_de.properties` (default) and `messages.properties`
     *  (English backup); `JournalEventLabelsTest` pins that completeness for both bundles.
     *  Delegates to [InvoiceAuditEvent.labelKey] so this view and the `Journal` export sheet
     *  always resolve the exact same key. */
    fun labelKey(event: InvoiceAuditEvent): String = event.labelKey

    /** Chip tone for [event]: created and assigned read ok, updated warn, deleted and unassigned storno. */
    fun tone(event: InvoiceAuditEvent): ChipTone = when (verbOf(event)) {
        Verb.CREATED, Verb.ASSIGNED -> ChipTone.OK
        Verb.UPDATED -> ChipTone.WARN
        Verb.DELETED, Verb.UNASSIGNED -> ChipTone.STORNO
    }

    /** `.journal-tile--*` modifier for [event] — see this object's own KDoc for the verb→colour
     *  mapping. */
    fun cssModifier(event: InvoiceAuditEvent): String = when (verbOf(event)) {
        Verb.CREATED -> "success"
        Verb.UPDATED -> "warning"
        Verb.DELETED -> "cancelled"
        Verb.ASSIGNED -> "success"
        Verb.UNASSIGNED -> "cancelled"
    }

    /** The verbs every [InvoiceAuditEvent] ends in. Public because [JournalEventIcons] maps the
     *  same verbs to symbols, and both mappings must agree on what a given event *is*. */
    enum class Verb { CREATED, UPDATED, DELETED, ASSIGNED, UNASSIGNED }

    /** The captured table an event belongs to — the second axis [JournalEventIcons] renders (the
     *  verb says what happened, this says to what). [TRIP_EXPENSE] was added alongside the new
     *  `trip_expense` capture trigger. */
    enum class Entity { INVOICE, ITEM, DOCUMENT, TRIP_EXPENSE, RECEIPT }

    /** The four captured tables (invoice/invoice_item/invoice_document/trip_expense) each
     *  contribute one CREATED/UPDATED/DELETED triple, in that fixed order — see
     *  [InvoiceAuditEvent]'s own KDoc. An exhaustive `when(event)` over the enum itself rather than
     *  a name-suffix `when` with an `error()` fallback: the compiler, not a runtime throw, now
     *  rejects a future [InvoiceAuditEvent] entry added without updating this mapping — strictly
     *  stronger than the reordering-detection [InvoiceAuditEvent.fromOrdinal] already does, and the
     *  fallback branch it replaced was unreachable dead code (every existing entry already matched
     *  one of the five suffixes). */
    fun verbOf(event: InvoiceAuditEvent): Verb = when (event) {
        InvoiceAuditEvent.INVOICE_CREATED,
        InvoiceAuditEvent.INVOICE_ITEM_CREATED,
        InvoiceAuditEvent.INVOICE_DOCUMENT_CREATED,
        InvoiceAuditEvent.TRIP_EXPENSE_CREATED,
        -> Verb.CREATED

        InvoiceAuditEvent.INVOICE_UPDATED,
        InvoiceAuditEvent.INVOICE_ITEM_UPDATED,
        InvoiceAuditEvent.INVOICE_DOCUMENT_UPDATED,
        InvoiceAuditEvent.TRIP_EXPENSE_UPDATED,
        -> Verb.UPDATED

        InvoiceAuditEvent.INVOICE_DELETED,
        InvoiceAuditEvent.INVOICE_ITEM_DELETED,
        InvoiceAuditEvent.INVOICE_DOCUMENT_DELETED,
        InvoiceAuditEvent.TRIP_EXPENSE_DELETED,
        -> Verb.DELETED

        InvoiceAuditEvent.INVOICE_RECEIPT_ASSIGNED,
        InvoiceAuditEvent.ITEM_RECEIPT_ASSIGNED,
        -> Verb.ASSIGNED

        InvoiceAuditEvent.INVOICE_RECEIPT_UNASSIGNED,
        InvoiceAuditEvent.ITEM_RECEIPT_UNASSIGNED,
        -> Verb.UNASSIGNED
    }

    /** Read off the enum constant's own name prefix, for the same reason [verbOf] reads the
     *  suffix: legible, and immune to a future reordering of [InvoiceAuditEvent]. */
    fun entityOf(event: InvoiceAuditEvent): Entity = when {
        event.name.startsWith("INVOICE_ITEM_") -> Entity.ITEM
        event.name.startsWith("INVOICE_DOCUMENT_") -> Entity.DOCUMENT
        event.name.startsWith("TRIP_EXPENSE_") -> Entity.TRIP_EXPENSE
        event.name.contains("_RECEIPT_") -> Entity.RECEIPT
        else -> Entity.INVOICE
    }

    /** i18n key for [entityOf]'s result — the journal grid names the affected object next to the
     *  event chip, so a reader sees "which of the three tables" without decoding the label text. */
    fun entityLabelKey(event: InvoiceAuditEvent): String = "journal.entity.${entityOf(event).name}"
}
