package de.littletrotter.ui.view.journal.composer

import de.littletrotter.application.invoice.InvoiceAuditActionLine
import de.littletrotter.domain.compliance.InvoiceAuditJournalEntry
import de.littletrotter.ui.view.journal.JournalDetailRendering
import de.littletrotter.ui.view.journal.JournalDetailRenderer
import de.littletrotter.ui.view.journal.JournalRowIdentity

/**
 * Everything a composer needs beyond the action's own rows: translation, plus the small
 * batched name lookups a sentence needs but a journal row never carries verbatim — a recipient's
 * display name (`invoice.recipient_id` is only a foreign key), a receipt's file name
 * (`invoice_receipt`/`invoice_item_receipt` carry only `receipt_id`) and an item's own position
 * number (`invoice_item_receipt`/`trip_expense` carry only `invoice_item_id`, a database primary
 * key, never the auditor-facing position). All three maps are built ONCE per page by
 * [de.littletrotter.ui.view.journal.JournalActionContent] (batched, never per line) and simply miss
 * an entry when nothing needed it or a lookup came back empty — every composer treats a missing
 * entry the same way it treats an absent JSON field (fall back, never crash).
 */
data class JournalComposerContext(
    val tr: (String, List<Any>) -> String,
    val trOrNull: (String) -> String?,
    val recipientNames: Map<Long, String> = emptyMap(),
    val receiptFileNames: Map<Long, String> = emptyMap(),
    val itemPositions: Map<Long, String> = emptyMap(),
) {
    /** JournalValueFormatter/JournalFieldLabels-style single-argument resolver, for the
     *  handful of call sites that need it in that shape. */
    val trOne: (String) -> String get() = { key -> tr(key, emptyList()) }
}

/**
 * One composer per action family (status, items, receipts, header/recipient,
 * documents/validation, storno, trip, external — see [de.littletrotter.ui.view.journal
 * .JournalActionContent], the dispatcher every grid cell actually calls), each building the "Was
 * genau" sentence for its own [de.littletrotter.domain.compliance.InvoiceAuditAction]s out of the
 * action's technical rows. Kept as small, independently unit-testable classes rather than one
 * `when`-monster in a grid column — a family that grows a new sentence variant touches only its
 * own file.
 *
 * Returns `null`/blank when the action's rows carry nothing worth naming (a bookkeeping-only
 * update) — [de.littletrotter.ui.view.journal.JournalActionContent] is the one place that turns
 * that into the fixed "keine fachliche Änderung" fallback, so no composer has to repeat
 * it.
 */
fun interface JournalWhatComposer {
    fun compose(line: InvoiceAuditActionLine, ctx: JournalComposerContext): String?
}

/** Shared helpers every composer needs: finding a row's diff for one column, defensively (a
 *  malformed payload or an event with no diff shape at all never throws — same "one row's display
 *  bug must never break the rest" rule [JournalDetailRenderer] itself follows). */
internal object JournalComposerSupport {

    /** The first changed value of [column] across [rows]' `_UPDATED` diffs, or `null` when no row
     *  touched it — an unknown/absent column simply yields no sentence fragment, never a
     *  crash. */
    fun firstDiff(rows: List<InvoiceAuditJournalEntry>, column: String): Pair<String?, String?>? {
        for (row in rows) {
            val rendering = JournalDetailRenderer.render(row.event, row.detail)
            val change = (rendering as? JournalDetailRendering.Diff)?.changes?.firstOrNull { it.field == column }
            if (change != null) return change.oldValue to change.newValue
        }
        return null
    }

    fun firstSnapshot(rows: List<InvoiceAuditJournalEntry>, column: String): String? {
        for (row in rows) {
            val rendering = JournalDetailRenderer.render(row.event, row.detail)
            val field = (rendering as? JournalDetailRendering.Snapshot)?.fields?.firstOrNull { it.field == column }
            if (field != null) return field.value
        }
        return null
    }

    /** [row]'s `_row` identity metadata (its own id plus, where the table has one, a
     *  human-meaningful business key — see [JournalRowIdentity]'s own KDoc), or `null` for an
     *  INSERT/DELETE row (their full snapshot already carries an id/position number as an ordinary
     *  field) or an UPDATE row the trigger wrote before `_row` existed. */
    fun rowIdentity(row: InvoiceAuditJournalEntry): JournalRowIdentity? =
        (JournalDetailRenderer.render(row.event, row.detail) as? JournalDetailRendering.Diff)?.rowIdentity
}
