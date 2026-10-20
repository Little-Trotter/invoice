package de.littletrotter.ui.view.journal.composer

import de.littletrotter.application.dto.InvoiceAuditActionLine
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import de.littletrotter.ui.view.journal.JournalValueFormatter

/**
 * The "status" family: [de.littletrotter.domain.compliance.InvoiceAuditAction.STATUS_CHANGED],
 * [INVOICE_CREATED][de.littletrotter.domain.compliance.InvoiceAuditAction.INVOICE_CREATED],
 * [SEND_REQUESTED][de.littletrotter.domain.compliance.InvoiceAuditAction.SEND_REQUESTED] and
 * [INVOICE_DELETED][de.littletrotter.domain.compliance.InvoiceAuditAction.INVOICE_DELETED] — every
 * action whose "Was genau" is fundamentally "which lifecycle state did this invoice move to".
 *
 * `invoice.status` is resolved through [JournalValueFormatter] (the exact same enum-ordinal
 * decoding the technical-row dialog uses), never re-decoded here — a backwards transition
 * (`IN_REVIEW → DRAFT`) renders exactly like a forward one, since old/new are handled
 * symmetrically.
 *
 * B5's own "override reason" half: [de.littletrotter.application.invoice.InvoiceLifecycleService
 * .transition]'s `overrideReason` parameter is never persisted onto the `invoice` row (only logged,
 * see [de.littletrotter.application.invoice.FinalizationPolicy.enforceFinalizationGate]), so no
 * journal row diff can ever carry it — capturing it durably would mean touching
 * `InvoiceLifecycleService`/the baseline, both out of scope here. This composer still
 * looks for an `override_reason` diff column defensively (harmless when absent, and forward-
 * compatible if a later package starts persisting it).
 */
object StatusFamilyComposer : JournalWhatComposer {

    private const val STATUS_COLUMN = "status"
    private const val OVERRIDE_REASON_COLUMN = "override_reason"

    override fun compose(line: InvoiceAuditActionLine, ctx: JournalComposerContext): String? {
        val tr = ctx.tr
        val trOne = ctx.trOne

        if (line.rows.any { it.event == InvoiceAuditEvent.INVOICE_DELETED }) {
            return tr("journal.what.invoiceDeleted", emptyList())
        }

        val diff = JournalComposerSupport.firstDiff(line.rows, STATUS_COLUMN)
        val sentence = if (diff != null) {
            val (old, new) = diff
            // JournalValueFormatter.format returns null exactly when its raw parameter is null
            // (see that object's own "`null` in, `null` out" KDoc), so each elvis below only ever
            // triggers when old/new is null — the "value non-null" arm of an inlined `.orEmpty()`
            // could never run; "" replaces it with no behaviour change.
            val oldLabel = JournalValueFormatter.format(InvoiceAuditEvent.INVOICE_UPDATED, STATUS_COLUMN, old, trOne) ?: ""
            val newLabel = JournalValueFormatter.format(InvoiceAuditEvent.INVOICE_UPDATED, STATUS_COLUMN, new, trOne) ?: ""
            tr("journal.what.statusChanged", listOf(oldLabel, newLabel))
        } else {
            val snapshotStatus = JournalComposerSupport.firstSnapshot(line.rows, STATUS_COLUMN) ?: return null
            // snapshotStatus is non-null here (the elvis above already returned on null), and
            // JournalValueFormatter.format returns null only when its raw parameter is null, so
            // this elvis's fallback arm is unreachable. Left as a plain elvis (not rewritten to a
            // `!!`) since that would only relocate the same dead null-check, not remove it.
            val label = JournalValueFormatter.format(InvoiceAuditEvent.INVOICE_CREATED, STATUS_COLUMN, snapshotStatus, trOne) ?: snapshotStatus
            tr("journal.what.statusSet", listOf(label))
        }

        val overrideReason = JournalComposerSupport.firstDiff(line.rows, OVERRIDE_REASON_COLUMN)?.second
            ?: JournalComposerSupport.firstSnapshot(line.rows, OVERRIDE_REASON_COLUMN)
        return if (overrideReason.isNullOrBlank()) sentence else tr("journal.what.overrideReason", listOf(sentence, overrideReason))
    }
}
