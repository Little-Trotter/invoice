package de.littletrotter.ui.view.journal.composer

import de.littletrotter.application.dto.InvoiceAuditActionLine
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import de.littletrotter.domain.compliance.InvoiceAuditJournalEntry

/**
 * The "receipts" family:
 * [RECEIPT_ASSIGNED][de.littletrotter.domain.compliance.InvoiceAuditAction.RECEIPT_ASSIGNED] and
 * [RECEIPT_DETACHED][de.littletrotter.domain.compliance.InvoiceAuditAction.RECEIPT_DETACHED]. Both
 * actions journal a flat row event (`ReceiptLinkService.setReceiptsFor*` journals one
 * `RECEIPT_ASSIGNED`/`RECEIPT_DETACHED` action regardless of how many links actually changed — see
 * the spec's own "facts to design around"); what really happened per link is only visible in the
 * technical rows' own EVENTS (`INVOICE_RECEIPT_*`/`ITEM_RECEIPT_*`), so this composer reads those,
 * never the action alone.
 *
 * `invoice_receipt`/`invoice_item_receipt` carry only `receipt_id` (no file name — their own
 * baseline column list), so the sentence's file name comes from [JournalComposerContext
 * .receiptFileNames], a batched lookup built once per page, never per row. Same shape for the
 * item-scope sentence's position number: `invoice_item_receipt` carries only `invoice_item_id` (a
 * database primary key, meaningless to an auditor) — [JournalComposerContext.itemPositions]
 * resolves it to the position number `ItemFamilyComposer` itself names.
 */
object ReceiptFamilyComposer : JournalWhatComposer {

    private const val RECEIPT_ID_COLUMN = "receipt_id"
    private const val INVOICE_ITEM_ID_COLUMN = "invoice_item_id"

    override fun compose(line: InvoiceAuditActionLine, ctx: JournalComposerContext): String? {
        val tr = ctx.tr
        val links = line.rows.mapNotNull { row -> toLink(row) }
        if (links.isEmpty()) return null

        val phrases = links.map { link ->
            val fileName = ctx.receiptFileNames[link.receiptId] ?: tr("journal.what.receiptUnknown", emptyList())
            val scope = if (link.invoiceItemId != null) {
                // The item may no longer exist (a hard-deleted DRAFT position) — a neutral fallback,
                // never the bare database id an auditor cannot act on.
                val position = ctx.itemPositions[link.invoiceItemId] ?: tr("journal.what.itemPositionUnknown", emptyList())
                tr("journal.what.receiptScopeItem", listOf(position))
            } else {
                tr("journal.what.receiptScopeInvoice", emptyList())
            }
            val key = if (link.assigned) "journal.what.receiptAssigned" else "journal.what.receiptDetached"
            tr(key, listOf(fileName, scope))
        }
        return phrases.distinct().joinToString("; ")
    }

    private data class ReceiptLink(val receiptId: Long, val invoiceItemId: Long?, val assigned: Boolean)

    private fun toLink(row: InvoiceAuditJournalEntry): ReceiptLink? {
        val assigned = when (row.event) {
            InvoiceAuditEvent.INVOICE_RECEIPT_ASSIGNED, InvoiceAuditEvent.ITEM_RECEIPT_ASSIGNED -> true
            InvoiceAuditEvent.INVOICE_RECEIPT_UNASSIGNED, InvoiceAuditEvent.ITEM_RECEIPT_UNASSIGNED -> false
            else -> return null
        }
        val receiptId = JournalComposerSupport.firstSnapshot(listOf(row), RECEIPT_ID_COLUMN)?.toLongOrNull() ?: return null
        val invoiceItemId = if (row.event == InvoiceAuditEvent.ITEM_RECEIPT_ASSIGNED || row.event == InvoiceAuditEvent.ITEM_RECEIPT_UNASSIGNED) {
            JournalComposerSupport.firstSnapshot(listOf(row), INVOICE_ITEM_ID_COLUMN)?.toLongOrNull()
        } else {
            null
        }
        return ReceiptLink(receiptId, invoiceItemId, assigned)
    }
}
