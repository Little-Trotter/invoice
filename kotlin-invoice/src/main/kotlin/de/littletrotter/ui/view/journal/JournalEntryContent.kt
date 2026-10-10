package de.littletrotter.ui.view.journal

import de.littletrotter.domain.compliance.InvoiceAuditJournalEntry

/**
 * One display line of a journal entry's payload: a translated field label plus either an old→new
 * pair (UPDATE) or a single value (INSERT/DELETE snapshot). Both values are already formatted by
 * [JournalValueFormatter]; `null` means "no value", which every renderer shows as the same
 * placeholder.
 */
data class JournalEntryLine(
    val field: String,
    val label: String,
    val oldValue: String?,
    val newValue: String?,
    val isChange: Boolean,
) {
    /** A snapshot line with nothing in it — the ~50-column `to_jsonb(NEW)` payload of a fresh
     *  invoice is mostly these, which is why the dialog hides them behind a toggle. */
    val isEmpty: Boolean get() = !isChange && newValue == null
}

/**
 * What the grid shows about an entry without opening it: how much changed, and the first line or
 * two, by name. [changeCount] counts every line the entry carries — changed columns for an UPDATE,
 * populated columns for a snapshot — so the grid can say "4 Felder geändert" instead of spilling
 * four raw JSON fragments into a cell that only ever showed the first one, ellipsised.
 */
data class JournalEntrySummary(
    val lines: List<JournalEntryLine>,
    val changeCount: Int,
    val totalCount: Int,
    val isChange: Boolean,
) {
    /** Lines worth naming: an UPDATE's are all changes; a snapshot's empty columns are skipped,
     *  since "Kostenstelle: —" is not something a reader wants a row's few words spent on. */
    fun namedLines(): List<JournalEntryLine> = lines.filter { it.isChange || !it.isEmpty }

    /** The field labels the grid names outright before falling back to "+n weitere". */
    fun previewLabels(): List<String> = namedLines().take(PREVIEW_LIMIT).map { it.label }

    val hiddenCount: Int get() = (changeCount - PREVIEW_LIMIT).coerceAtLeast(0)

    companion object {
        /** How many field names fit one grid row without wrapping — three, matched to the summary
         *  column's width; the rest is counted, not listed. */
        const val PREVIEW_LIMIT = 3
    }
}

/**
 * Assembles what both journal views render, out of the three pure pieces around it:
 * [JournalDetailRenderer] (JSON → diff or snapshot), [JournalFieldLabels] (column → label) and
 * [JournalValueFormatter] (raw value → readable value). Kept Vaadin-free so the whole
 * payload-to-text path is unit-testable without a UI — only the components in
 * [JournalGridColumns]/[JournalEntryDialog] beyond it need one.
 *
 * A snapshot's empty columns are kept in [JournalEntrySummary.lines] rather than dropped here: the
 * dialog's "show empty fields" toggle needs both the filtered and the full view, and dropping them
 * at this layer would also make [JournalEntrySummary.totalCount] a lie.
 */
object JournalEntryContent {

    /** [trOrNull] resolves a possibly-undefined `journal.field.*` key (see
     *  [JournalFieldLabels.resolve]); [tr] resolves keys that always exist (`app.yes`, status
     *  labels). Two parameters rather than one because the two contracts genuinely differ. */
    fun summarize(
        entry: InvoiceAuditJournalEntry,
        trOrNull: (String) -> String?,
        tr: (String) -> String,
    ): JournalEntrySummary {
        val lines = when (val rendering = JournalDetailRenderer.render(entry.event, entry.detail)) {
            is JournalDetailRendering.Diff -> rendering.changes.map { change ->
                JournalEntryLine(
                    field = change.field,
                    label = JournalFieldLabels.resolve(change.field, trOrNull),
                    oldValue = JournalValueFormatter.format(entry.event, change.field, change.oldValue, tr),
                    newValue = JournalValueFormatter.format(entry.event, change.field, change.newValue, tr),
                    isChange = true,
                )
            }
            is JournalDetailRendering.Snapshot -> rendering.fields.map { field ->
                JournalEntryLine(
                    field = field.field,
                    label = JournalFieldLabels.resolve(field.field, trOrNull),
                    oldValue = null,
                    newValue = JournalValueFormatter.format(entry.event, field.field, field.value, tr),
                    isChange = false,
                )
            }
        }
        val isChange = lines.firstOrNull()?.isChange ?: entry.event.name.endsWith("_UPDATED")
        // An UPDATE's lines are already only the changed columns (the trigger diffs before it
        // writes); a snapshot's "count" is the columns that actually carry something.
        val changeCount = if (isChange) lines.size else lines.count { !it.isEmpty }
        return JournalEntrySummary(lines, changeCount, lines.size, isChange)
    }
}
