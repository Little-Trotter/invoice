package de.littletrotter.ui.view.journal.composer

import de.littletrotter.application.invoice.InvoiceAuditActionLine
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import de.littletrotter.domain.compliance.InvoiceAuditJournalEntry
import de.littletrotter.shared.Money
import de.littletrotter.ui.format.MoneyFormatter
import de.littletrotter.ui.view.journal.JournalDetailRendering
import de.littletrotter.ui.view.journal.JournalDetailRenderer
import de.littletrotter.ui.view.journal.JournalFieldLabels
import de.littletrotter.ui.view.journal.JournalGrossDelta
import de.littletrotter.ui.view.journal.JournalValueFormatter

/**
 * The "items" family:
 * [ITEM_ADDED][de.littletrotter.domain.compliance.InvoiceAuditAction.ITEM_ADDED],
 * [ITEM_CHANGED][de.littletrotter.domain.compliance.InvoiceAuditAction.ITEM_CHANGED],
 * [ITEM_DELETED][de.littletrotter.domain.compliance.InvoiceAuditAction.ITEM_DELETED] and
 * [RECALCULATED][de.littletrotter.domain.compliance.InvoiceAuditAction.RECALCULATED] — every
 * action whose "Was genau" centers on `invoice_item` (position number, description, quantity,
 * price). `addItem`'s own internal recalculation never surfaces as a separate line (the
 * "outermost wins" rule): the `invoice_item` row IS the item-level technical row of the SAME
 * `ITEM_ADDED` action, this composer never has to reassemble it from two actions.
 */
object ItemFamilyComposer : JournalWhatComposer {

    private const val POSITION_COLUMN = "position_id"
    private const val DESCRIPTION_COLUMN = "description"
    private const val QUANTITY_COLUMN = "quantity"
    private const val UNIT_PRICE_COLUMN = "unit_price_net"

    override fun compose(line: InvoiceAuditActionLine, ctx: JournalComposerContext): String? {
        val tr = ctx.tr
        val itemRow = line.rows.firstOrNull {
            it.event == InvoiceAuditEvent.INVOICE_ITEM_CREATED ||
                it.event == InvoiceAuditEvent.INVOICE_ITEM_UPDATED ||
                it.event == InvoiceAuditEvent.INVOICE_ITEM_DELETED
        }

        if (itemRow == null) {
            // RECALCULATED on its own touches only the invoice header's totals — no invoice_item
            // row exists to describe a position from.
            val delta = JournalGrossDelta.compute(line) ?: return null
            return tr("journal.what.recalculated", listOf(MoneyFormatter.format(delta)))
        }

        // An UPDATE diff only lists changed columns, so the position number comes from the
        // reserved `_row` identity (the trigger writes it unconditionally, even when
        // the position itself never changed); an INSERT/DELETE's full snapshot already carries
        // position_id as an ordinary field. The `firstDiff` fallback covers a diff written before
        // `_row` existed (position_id itself is the changed column, an edge case its own test below
        // exercises) — never a crash either way.
        val position = when (itemRow.event) {
            InvoiceAuditEvent.INVOICE_ITEM_UPDATED ->
                JournalComposerSupport.rowIdentity(itemRow)?.positionId
                    ?: JournalComposerSupport.firstDiff(line.rows, POSITION_COLUMN)?.second
            else -> JournalComposerSupport.firstSnapshot(line.rows, POSITION_COLUMN)
        }

        return when (itemRow.event) {
            InvoiceAuditEvent.INVOICE_ITEM_DELETED -> {
                val description = JournalComposerSupport.firstSnapshot(line.rows, DESCRIPTION_COLUMN)
                tr("journal.what.itemDeleted", listOf(position.orEmpty(), description.orEmpty()))
            }
            InvoiceAuditEvent.INVOICE_ITEM_CREATED -> {
                val description = JournalComposerSupport.firstSnapshot(line.rows, DESCRIPTION_COLUMN).orEmpty()
                val quantity = JournalComposerSupport.firstSnapshot(line.rows, QUANTITY_COLUMN)
                val price = JournalComposerSupport.firstSnapshot(line.rows, UNIT_PRICE_COLUMN)?.let(::formatPrice)
                tr("journal.what.itemAdded", listOf(position.orEmpty(), description, quantity.orEmpty(), price.orEmpty()))
            }
            else -> composeItemChanged(itemRow, position, ctx)
        }
    }

    // Every changed column names itself, not just a curated whitelist: an
    // unknown column still gets its own sentence fragment via JournalFieldLabels'
    // humanize fallback, never a silently dropped change or a `null` sentence.
    // itemRow.event is always INVOICE_ITEM_UPDATED in this arm (CREATED/DELETED are the other
    // two `when` arms in [compose]), and JournalDetailRenderer.render decides Diff vs Snapshot
    // purely from the event name ending "_UPDATED" (isUpdateEvent) — it returns a Diff
    // for every _UPDATED event, even on a malformed/empty payload (emptyRenderingFor
    // still branches on isUpdateEvent), never a Snapshot. The cast can never fail here;
    // same "unconditional cast, fails loudly instead of silently dropping data"
    // reasoning as B2bProfileFieldsSection.setGroupedFields.
    private fun composeItemChanged(
        itemRow: InvoiceAuditJournalEntry,
        position: String?,
        ctx: JournalComposerContext,
    ): String? {
        val rendering = JournalDetailRenderer.render(itemRow.event, itemRow.detail)
        val changes = (rendering as JournalDetailRendering.Diff).changes
            .filterNot { it.field == POSITION_COLUMN }
        if (changes.isEmpty()) return null
        val phrase = changes.joinToString("; ") { change ->
            val label = JournalFieldLabels.resolve(change.field, ctx.trOrNull)
            val oldLabel = formatItemFieldValue(itemRow.event, change.field, change.oldValue, ctx.trOne)
            val newLabel = formatItemFieldValue(itemRow.event, change.field, change.newValue, ctx.trOne)
            "$label: $oldLabel → $newLabel"
        }
        return ctx.tr("journal.what.itemChanged", listOf(position.orEmpty(), phrase))
    }

    /** [UNIT_PRICE_COLUMN] is money (project convention: always through [Money], never a raw
     *  numeric string) — every other field this composer names still resolves through
     *  [JournalValueFormatter] (enum ordinals, timestamps). */
    private fun formatItemFieldValue(event: InvoiceAuditEvent, column: String, value: String?, tr: (String) -> String): String =
        if (column == UNIT_PRICE_COLUMN) {
            // formatPrice always returns non-null (its own elvis falls back to the raw text), so
            // `value?.let(::formatPrice)` is null exactly when value itself is null — the "value
            // non-null" arm of an inlined `.orEmpty()` here could never run; "" replaces it with no
            // behaviour change.
            value?.let(::formatPrice) ?: ""
        } else {
            // Same reasoning via JournalValueFormatter.format's own "null in, null out" contract
            // (see that object's KDoc): this elvis only ever triggers when value is null.
            JournalValueFormatter.format(event, column, value, tr) ?: ""
        }

    private fun formatPrice(raw: String): String =
        raw.toBigDecimalOrNull()?.let { MoneyFormatter.format(Money.of(it)) } ?: raw
}
