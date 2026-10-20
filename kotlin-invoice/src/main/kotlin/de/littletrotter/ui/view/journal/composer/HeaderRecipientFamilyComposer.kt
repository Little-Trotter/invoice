package de.littletrotter.ui.view.journal.composer

import de.littletrotter.application.dto.InvoiceAuditActionLine
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import de.littletrotter.domain.compliance.InvoiceAuditJournalEntry
import de.littletrotter.ui.view.journal.JournalDetailRendering
import de.littletrotter.ui.view.journal.JournalDetailRenderer
import de.littletrotter.ui.view.journal.JournalFieldLabels
import de.littletrotter.ui.view.journal.JournalValueFormatter

/**
 * The "header/recipient" family:
 * [HEADER_CHANGED][de.littletrotter.domain.compliance.InvoiceAuditAction.HEADER_CHANGED],
 * [RECIPIENT_CHANGED][de.littletrotter.domain.compliance.InvoiceAuditAction.RECIPIENT_CHANGED] and
 * [REVERSE_CHARGE_DECIDED][de.littletrotter.domain.compliance.InvoiceAuditAction
 * .REVERSE_CHARGE_DECIDED].
 *
 * A recipient change made through `updateHeader` journals as `HEADER_CHANGED` (only
 * `refreshPartySnapshots` journals the dedicated `RECIPIENT_CHANGED` — see the spec's own "facts to
 * design around"), so this composer derives "Empfänger geändert: A → B" from the row's own
 * `recipient_id` diff, never from the action alone: whichever of the two actions a transaction
 * carries, a `recipient_id` change always wins the sentence over any other header field that also
 * changed in the same transaction. `invoice.recipient_id` is only a foreign key — the names come
 * from [JournalComposerContext.recipientNames], a batched lookup built once per page.
 */
object HeaderRecipientFamilyComposer : JournalWhatComposer {

    private const val RECIPIENT_ID_COLUMN = "recipient_id"
    private const val REVERSE_CHARGE_COLUMN = "reverse_charge"

    /** Shown at most this many other header fields before collapsing into "+n weitere" — same
     *  preview-limit idea JournalEntrySummary.PREVIEW_LIMIT already uses for the technical row. */
    private const val PREVIEW_LIMIT = 3

    override fun compose(line: InvoiceAuditActionLine, ctx: JournalComposerContext): String? {
        val invoiceRow = line.rows.firstOrNull { it.event == InvoiceAuditEvent.INVOICE_UPDATED } ?: return null
        return composeRecipientChange(line, ctx)
            ?: composeReverseChargeChange(line, invoiceRow, ctx)
            ?: composeOtherHeaderChange(invoiceRow, ctx)
    }

    private fun composeRecipientChange(line: InvoiceAuditActionLine, ctx: JournalComposerContext): String? {
        val (oldId, newId) = JournalComposerSupport.firstDiff(line.rows, RECIPIENT_ID_COLUMN) ?: return null
        val oldName = recipientName(oldId, ctx)
        val newName = recipientName(newId, ctx)
        return ctx.tr("journal.what.recipientChanged", listOf(oldName, newName))
    }

    private fun composeReverseChargeChange(
        line: InvoiceAuditActionLine,
        invoiceRow: InvoiceAuditJournalEntry,
        ctx: JournalComposerContext,
    ): String? {
        val (old, new) = JournalComposerSupport.firstDiff(line.rows, REVERSE_CHARGE_COLUMN) ?: return null
        // JournalValueFormatter.format returns null iff its raw argument is null (blank-but-
        // non-null input still formats to ""), so whenever the elvis fallback below runs,
        // old/new is provably null already — a plain "" replaces the dead old?/new?.orEmpty()
        // null-check that could never see a non-null receiver.
        val oldLabel = JournalValueFormatter.format(invoiceRow.event, REVERSE_CHARGE_COLUMN, old, ctx.trOne) ?: ""
        val newLabel = JournalValueFormatter.format(invoiceRow.event, REVERSE_CHARGE_COLUMN, new, ctx.trOne) ?: ""
        return ctx.tr("journal.what.reverseChargeChanged", listOf(oldLabel, newLabel))
    }

    // invoiceRow.event is always INVOICE_UPDATED (guaranteed by [compose]'s own firstOrNull
    // filter), and JournalDetailRenderer.render always returns a Diff for an *_UPDATED event — so
    // this is never the Snapshot arm; an unconditional cast replaces a permanently-unreachable
    // `as? ... ?: emptyList()` fallback.
    private fun composeOtherHeaderChange(invoiceRow: InvoiceAuditJournalEntry, ctx: JournalComposerContext): String? {
        val rendering = JournalDetailRenderer.render(invoiceRow.event, invoiceRow.detail) as JournalDetailRendering.Diff
        val changes = rendering.changes
        if (changes.isEmpty()) return null

        // An unknown/uncurated column falls back to its own raw name via
        // JournalFieldLabels.resolve, exactly like the technical-row dialog does; never a crash.
        val phrase = changes.take(PREVIEW_LIMIT).joinToString("; ") { change ->
            val label = JournalFieldLabels.resolve(change.field, ctx.trOrNull)
            // Same guarantee as the reverse-charge branch above: format() only returns null for a
            // null raw value, so the fallback below is never reached with a non-null oldValue/newValue.
            val oldLabel = JournalValueFormatter.format(invoiceRow.event, change.field, change.oldValue, ctx.trOne) ?: ""
            val newLabel = JournalValueFormatter.format(invoiceRow.event, change.field, change.newValue, ctx.trOne) ?: ""
            "$label: $oldLabel → $newLabel"
        }
        val hidden = (changes.size - PREVIEW_LIMIT).coerceAtLeast(0)
        return if (hidden > 0) {
            ctx.tr("journal.what.headerChangedMore", listOf(phrase, hidden))
        } else {
            ctx.tr("journal.what.headerChanged", listOf(phrase))
        }
    }

    private fun recipientName(rawId: String?, ctx: JournalComposerContext): String =
        rawId?.toLongOrNull()?.let { ctx.recipientNames[it] } ?: ctx.tr("journal.what.recipientUnknown", emptyList())
}
