package de.littletrotter.ui.view.journal

import de.littletrotter.application.dto.InvoiceAuditActionLine
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.ui.view.journal.composer.DocumentValidationFamilyComposer
import de.littletrotter.ui.view.journal.composer.ExternalWriteFamilyComposer
import de.littletrotter.ui.view.journal.composer.HeaderRecipientFamilyComposer
import de.littletrotter.ui.view.journal.composer.ItemFamilyComposer
import de.littletrotter.ui.view.journal.composer.JournalComposerContext
import de.littletrotter.ui.view.journal.composer.JournalWhatComposer
import de.littletrotter.ui.view.journal.composer.ReceiptFamilyComposer
import de.littletrotter.ui.view.journal.composer.StatusFamilyComposer
import de.littletrotter.ui.view.journal.composer.CancellationFamilyComposer
import de.littletrotter.ui.view.journal.composer.TripFamilyComposer

/**
 * The "Was genau" dispatcher: picks the one [JournalWhatComposer] that owns
 * [InvoiceAuditActionLine.action]'s family (see each composer's own KDoc for which actions it
 * covers) and turns its result into what the grid actually shows. [composerFor] is a `when`
 * compiled exhaustively over [InvoiceAuditAction] itself, so a future nineteenth action that
 * forgets a composer fails to COMPILE rather than rendering a blank cell.
 *
 * A composer returning `null`/blank (a bookkeeping-only update whose only changed columns are
 * already stripped by the trigger, or one this page's rows genuinely say nothing about) becomes the
 * fixed `journal.what.noChange` sentence: never a blank "Was genau" cell.
 */
object JournalActionContent {

    fun whatHappened(line: InvoiceAuditActionLine, ctx: JournalComposerContext): String {
        val sentence = composerFor(line.action).compose(line, ctx)
        return sentence?.takeIf { it.isNotBlank() } ?: ctx.tr("journal.what.noChange", emptyList())
    }

    private fun composerFor(action: InvoiceAuditAction): JournalWhatComposer = when (action) {
        InvoiceAuditAction.UNSPECIFIED -> ExternalWriteFamilyComposer
        InvoiceAuditAction.INVOICE_CREATED,
        InvoiceAuditAction.CREATED_FROM_QUOTE,
        InvoiceAuditAction.STATUS_CHANGED,
        InvoiceAuditAction.SEND_REQUESTED,
        InvoiceAuditAction.INVOICE_DELETED,
        -> StatusFamilyComposer
        InvoiceAuditAction.HEADER_CHANGED,
        InvoiceAuditAction.RECIPIENT_CHANGED,
        InvoiceAuditAction.REVERSE_CHARGE_DECIDED,
        -> HeaderRecipientFamilyComposer
        InvoiceAuditAction.ITEM_ADDED,
        InvoiceAuditAction.ITEM_CHANGED,
        InvoiceAuditAction.ITEM_DELETED,
        InvoiceAuditAction.RECALCULATED,
        -> ItemFamilyComposer
        InvoiceAuditAction.DOCUMENT_GENERATED,
        InvoiceAuditAction.VALIDATION_RECORDED,
        InvoiceAuditAction.DOCUMENT_REGENERATED,
        InvoiceAuditAction.DOCUMENT_RESTORED,
        InvoiceAuditAction.DOCUMENT_REPRODUCED,
        InvoiceAuditAction.DOCUMENT_REPRODUCTION_SEND_BLOCKED,
        -> DocumentValidationFamilyComposer
        InvoiceAuditAction.CANCELLATION -> CancellationFamilyComposer
        InvoiceAuditAction.RECEIPT_ASSIGNED,
        InvoiceAuditAction.RECEIPT_DETACHED,
        -> ReceiptFamilyComposer
        InvoiceAuditAction.TRIP_SAVED -> TripFamilyComposer
    }
}
