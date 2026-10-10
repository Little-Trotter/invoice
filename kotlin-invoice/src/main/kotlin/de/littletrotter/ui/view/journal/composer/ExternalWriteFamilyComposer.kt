package de.littletrotter.ui.view.journal.composer

import de.littletrotter.application.invoice.InvoiceAuditActionLine
import de.littletrotter.ui.view.journal.JournalDetailRenderer
import de.littletrotter.ui.view.journal.JournalDetailRendering
import de.littletrotter.ui.view.journal.JournalFieldLabels

/**
 * The "external" family:
 * [UNSPECIFIED][de.littletrotter.domain.compliance.InvoiceAuditAction.UNSPECIFIED], rendered
 * "Direkter Datenbankeingriff" in the warning tone when the actor is also a DB role (see
 * [de.littletrotter.ui.view.journal.JournalActorClassifier]). This composer only names WHICH
 * columns the outside write touched — the same column-name-only preview
 * [de.littletrotter.ui.view.journal.JournalGridColumns.summaryCell] used before, since a
 * direct SQL write is exactly the case an auditor most wants named field-by-field rather than
 * summarized away.
 */
object ExternalWriteFamilyComposer : JournalWhatComposer {

    /** Same preview-limit idea as [de.littletrotter.ui.view.journal.JournalEntrySummary
     *  .PREVIEW_LIMIT] — three field names fit the "Was genau" column without wrapping. */
    private const val PREVIEW_LIMIT = 3

    override fun compose(line: InvoiceAuditActionLine, ctx: JournalComposerContext): String? {
        val tr = ctx.tr
        val fieldNames = line.rows.flatMap { row ->
            when (val rendering = JournalDetailRenderer.render(row.event, row.detail)) {
                is JournalDetailRendering.Diff -> rendering.changes.map { it.field }
                is JournalDetailRendering.Snapshot -> rendering.fields.filter { it.value != null }.map { it.field }
            }
        }.distinct()
        if (fieldNames.isEmpty()) return null

        val labels = fieldNames.take(PREVIEW_LIMIT).map { JournalFieldLabels.resolve(it, ctx.trOrNull) }
        val phrase = labels.joinToString(", ")
        val hidden = (fieldNames.size - PREVIEW_LIMIT).coerceAtLeast(0)
        return if (hidden > 0) {
            tr("journal.what.externalWriteMore", listOf(fieldNames.size, phrase, hidden))
        } else {
            tr("journal.what.externalWrite", listOf(fieldNames.size, phrase))
        }
    }
}
