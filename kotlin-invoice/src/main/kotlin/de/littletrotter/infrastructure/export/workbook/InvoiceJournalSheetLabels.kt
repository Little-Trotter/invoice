package de.littletrotter.infrastructure.export.workbook

import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditEvent

/**
 * Every user-facing label the `Journal` sheet needs. [columnHeaders] must carry
 * exactly [InvoiceJournalSheetWriter.COLUMN_COUNT] entries, in the sheet's own fixed column order —
 * [InvoiceJournalSheetWriter.write] fails fast with a clear message otherwise, mirroring
 * [InvoiceHeaderSheetWriter.write]'s own guard.
 */
data class InvoiceJournalSheetLabels(
    val columnHeaders: List<String>,
    /** Resolves the `Ereignis` column — see [labelFor]'s own fallback-to-[Enum.name] behaviour for
     *  an event this map has no entry for. */
    val eventLabels: Map<InvoiceAuditEvent, String>,
    /** Written as the sheet's only row when the exported invoice has no journal entries at all —
     *  an absent sheet would look like a broken export, so this note stands in for the missing
     *  data instead. */
    val noEntriesNoteLabel: String,
    /** Resolves the `Vorgang` column, same [labelFor] fallback-to-[Enum.name] behaviour as
     *  [eventLabels]. Defaults to an empty map so the many pre-existing tests of this data class keep
     *  compiling; [labelFor] then falls back to the raw [InvoiceAuditAction] name. */
    val actionLabels: Map<InvoiceAuditAction, String> = emptyMap(),
)
