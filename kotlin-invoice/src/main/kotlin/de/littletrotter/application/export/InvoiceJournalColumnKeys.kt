package de.littletrotter.application.export

import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditEvent

/**
 * The i18n bundle keys behind every `Journal` sheet column header, English and
 * German both complete (`kotlin-invoice/src/main/resources/i18n/messages.properties`/
 * `messages_de.properties`). One place pins the key **order**, matching
 * [de.littletrotter.infrastructure.export.workbook.InvoiceJournalSheetWriter]'s own fixed column
 * order exactly — same role [InvoiceHeaderColumnKeys] plays for the `Rechnungen` sheet. This
 * package writes no OOXML itself and resolves no bundle — the actual translation lookup is
 * deliberately left to the caller.
 *
 * "Vorgang"/"Vorgangs-Nr." are APPENDED after the eleven pre-existing columns rather than
 * inserted next to "Ereignis": every existing column index (row-level detail, hashes) stays
 * unchanged, so the many pre-existing sheet-writer/mapper tests pinned to those indices need no
 * unrelated churn.
 */
object InvoiceJournalColumnKeys {

    private const val PREFIX = "invoiceExport.journal.column."

    /** Exactly [de.littletrotter.infrastructure.export.workbook.InvoiceJournalSheetWriter
     *  .COLUMN_COUNT] entries, in column order. */
    val ORDERED_KEYS: List<String> = listOf(
        "invoiceId", "invoiceNumber", "seq", "occurredAt", "event", "actorName", "actor",
        "detail", "documentSha256", "entryHash", "prevHash", "action", "businessTx",
    ).map { PREFIX + it }

    /** The `Ereignis` column's own label keys — identical to every [InvoiceAuditEvent.labelKey]
     *  (`journal.event.<NAME>`), the same keys the journal views already resolve, so the
     *  sheet and the UI always say the same word for the same event. Not under [PREFIX]: these are
     *  a deliberate reuse of an existing key namespace, not a new one of this sheet's own. */
    val EVENT_LABEL_KEYS: List<String> = InvoiceAuditEvent.entries.map { it.labelKey }

    /** The `Vorgang` column's own label keys — identical to every
     *  [InvoiceAuditAction.labelKey] (`journal.action.<NAME>`), same reuse-not-duplicate reasoning
     *  as [EVENT_LABEL_KEYS]. */
    val ACTION_LABEL_KEYS: List<String> = InvoiceAuditAction.entries.map { it.labelKey }

    /** The single note row written when the exported invoice has no journal entries at all —
     *  the sheet's own "an absent sheet would look like a broken export" rule. */
    const val NO_ENTRIES_NOTE_KEY = "invoiceExport.journal.noEntriesNote"
}
