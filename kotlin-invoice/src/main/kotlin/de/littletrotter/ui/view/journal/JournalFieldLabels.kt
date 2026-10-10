package de.littletrotter.ui.view.journal

/**
 * Turns a raw `invoice_audit_journal.detail` key — a database column name such as
 * `payment_terms_days` — into something a reader recognises. The trigger writes `to_jsonb(NEW)`
 * verbatim (`V1__baseline_schema.sql`, section 13), so the payload carries SQL identifiers, not
 * UI labels; showing those unchanged is what made the journal read like a table dump.
 *
 * Lookup order is deliberate: a curated `journal.field.<column>` key wins, and anything without
 * one falls back to [humanize] rather than to the raw identifier. That keeps a newly added column
 * legible on the day it appears — a missing translation degrades to "Cost center", never to
 * `cost_center` — and keeps the bundle free of an entry per column of three wide tables.
 *
 * Pure and Vaadin-free, like [JournalEventLabels] and [JournalDetailRenderer] next to it.
 */
object JournalFieldLabels {

    fun labelKey(column: String): String = "journal.field.$column"

    /** [trOrNull] returns `null` for an undefined key (the `UiTranslations.trOrNull` contract),
     *  which is what selects the [humanize] fallback. */
    fun resolve(column: String, trOrNull: (String) -> String?): String =
        trOrNull(labelKey(column)) ?: humanize(column)

    /** `payment_terms_days` → `Payment terms days`: split on `_`, capitalise the first word only.
     *  Sentence case, not Title Case — German labels are the default here and capitalising every
     *  word would read wrong for the mixed-language fallback set. */
    fun humanize(column: String): String {
        val words = column.split('_').filter { it.isNotBlank() }
        if (words.isEmpty()) return column
        return words.joinToString(" ").replaceFirstChar { it.uppercaseChar() }
    }
}
