package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ReceiptRowDto
import de.littletrotter.application.invoice.port.inbound.AttachReceiptsUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceReceiptQueryPort
import de.littletrotter.ui.i18n.TranslationProvider

/**
 * [ReceiptDialog]'s target-bound repository/service calls (A4: position or invoice, one
 * dialog), extracted out of that already-900+-line file into its own cohesive unit: everything
 * here dispatches on [target] and nothing here builds UI. Constructed once per [ReceiptDialog]
 * (not a CDI bean — it is genuinely per-dialog-instance state, the same lifetime `expectedVersion`
 * always had).
 *
 * Reads through [invoiceReceiptQueryPort], writes through [attachReceiptsUseCase].
 *
 * [uiContext] is the dialog's own [com.vaadin.flow.component.dialog.Dialog] ([ReceiptDialog.dlg]),
 * passed through only so [tr] resolves the same locale [ReceiptDialog] itself would.
 */

/**
 * One row of the Invoice target's attached grid: a receipt together with where its link lives
 * ("Zugeordnet an" column) and whether that link is read-only (a position link, editable only on
 * the position's own dialog). The same receipt can appear as more than one row — once per position
 * it is linked to, plus once more if it is also attached at invoice level — so the row carries its
 * own copy of the receipt and its own metadata instead of a side lookup keyed by identity; two rows
 * for the same receipt id are simply two distinct [AttachedReceiptRow] values, not two entries
 * colliding on one key. `locationLabel`/`readOnly` stay at their defaults for [ReceiptTarget.Item],
 * which never distinguishes rows this way.
 */
internal data class AttachedReceiptRow(
    val receipt: ReceiptRowDto,
    val locationLabel: String = "",
    val readOnly: Boolean = false,
    /** The position's own item id, for a read-only row's "Zur Position" button; `null`
     *  for an invoice-level row (never read-only) and for every [ReceiptTarget.Item] row. */
    val itemId: Long? = null,
)
internal class ReceiptTargetGateway(
    private val invoiceReceiptQueryPort: InvoiceReceiptQueryPort,
    private val attachReceiptsUseCase: AttachReceiptsUseCase,
    private val invoice: InvoiceDetailDto,
    private val target: ReceiptTarget,
    private val uiContext: Component,
) : TranslationProvider {
    override fun getComponent(): Component = uiContext

    /** Business context for log messages: which invoice/item this dialog operates on. */
    internal fun targetContext(): String = when (target) {
        is ReceiptTarget.Item -> "invoiceId=${invoice.id}, itemId=${target.itemId}"
        is ReceiptTarget.Invoice -> "invoiceId=${target.invoiceId}"
    }

    // The first write is protected by the DTO version captured when the dialog opened. After a
    // committed write the view refreshes its read model; subsequent writes in this still-open
    // dialog must use the current row rather than replaying the stale version.
    private var expectedVersion: Long? = invoice.version

    // Every caller (`reloadAttached`'s `if (unsaved) return` guard, `attachedInitial`'s
    // `if (unsaved) emptyList() else …`) only reaches this once [ReceiptDialog.unsaved] is false,
    // which by definition means the target's id is non-null; asserted rather than defended with
    // `?.let … ?: emptyList()`, the same idiom [addSelected]/[removeOne] below already use for the
    // identical invariant.
    internal fun findAttached(): List<AttachedReceiptRow> = when (target) {
        is ReceiptTarget.Item ->
            invoiceReceiptQueryPort.findReceiptsForItem(target.itemId!!).map { AttachedReceiptRow(it) }
        is ReceiptTarget.Invoice -> findAttachedForInvoice(target.invoiceId!!)
    }

    /**
     * fix — the Invoice target's attached tab is the complete "what substantiates this invoice"
     * view now, listing BOTH scopes: invoice-level rows (detachable here, as before) and
     * position-assigned rows (one row per (position, receipt) link, read-only — assigning or
     * removing those stays on the position's own [ReceiptTarget.Item] dialog). Was: only
     * [ReceiptLinkService.findReceiptsForInvoice], which silently hid every position-assigned receipt
     * from this dialog even though the Belege tab's card and the IR-2 budget both already counted
     * them — the bug this fixes (invoice 580: 2 item receipts, 0 invoice-level, dialog showed 0).
     *
     * A receipt row whose item id is missing from [invoice]'s own item list (a stale read racing a
     * concurrent edit) falls back to the item id itself rather than crashing — the row still shows
     * up, just without a resolved position label.
     *
     * Item rows are listed first, invoice-level rows last: `reloadAttached` (local to
     * [ReceiptDialog.open]) has a `justAddedIds` lookup (`rows.lastOrNull { it.id in justAddedIds
     * }`) must resolve to the freshly *invoice-level* added row even in the edge case where the
     * same receipt id is also linked to a position — every addition made from this dialog is
     * invoice-level, item rows are never written here.
     */
    private fun findAttachedForInvoice(invoiceId: Long): List<AttachedReceiptRow> {
        val invoiceLevel = invoiceReceiptQueryPort.findReceiptsForInvoice(invoiceId)
        val byItem = invoiceReceiptQueryPort.findItemReceiptsForInvoice(invoiceId)
        val positionIdByItemId = invoice.items.associate { it.id to it.positionId }

        val itemRows = byItem.flatMap { (itemId, receipts) ->
            val label = positionIdByItemId[itemId] ?: itemId.toString()
            receipts.map { AttachedReceiptRow(it, locationLabel = label, readOnly = true, itemId = itemId) }
        }
        val invoiceLevelRows = invoiceLevel.map {
            AttachedReceiptRow(it, locationLabel = tr("invoiceDetail.receipts.column.assignedTo.invoiceLevel"), readOnly = false)
        }
        return itemRows + invoiceLevelRows
    }

    internal fun addSelected(receiptIds: List<Long>) {
        when (target) {
            is ReceiptTarget.Item -> attachReceiptsUseCase.addReceiptsToItem(target.itemId!!, receiptIds, expectedVersion)
            is ReceiptTarget.Invoice -> attachReceiptsUseCase.addReceiptsToInvoice(target.invoiceId!!, receiptIds, expectedVersion)
        }
        expectedVersion = null
    }

    internal fun removeOne(receiptId: Long) {
        when (target) {
            is ReceiptTarget.Item -> attachReceiptsUseCase.removeReceiptsFromItem(target.itemId!!, listOf(receiptId), expectedVersion)
            is ReceiptTarget.Invoice -> attachReceiptsUseCase.removeReceiptsFromInvoice(target.invoiceId!!, listOf(receiptId), expectedVersion)
        }
        expectedVersion = null
    }

    /** The attached tab's bulk-detach counterpart to [removeOne]: the same dispatch, one
     *  call for the whole selection instead of one call per row. */
    internal fun removeMany(receiptIds: List<Long>) {
        when (target) {
            is ReceiptTarget.Item -> attachReceiptsUseCase.removeReceiptsFromItem(target.itemId!!, receiptIds, expectedVersion)
            is ReceiptTarget.Invoice -> attachReceiptsUseCase.removeReceiptsFromInvoice(target.invoiceId!!, receiptIds, expectedVersion)
        }
        expectedVersion = null
    }

    // excludeReceiptIds defaults to empty and is only ever non-empty for ReceiptTarget.Item
    // (the "this invoice's own receipts" group, kept out of the remaining pool below it).
    internal fun fetchAssignable(
        pageIndex: Int,
        pageSize: Int,
        filter: String?,
        excludeReceiptIds: Set<Long> = emptySet(),
    ): List<ReceiptRowDto> = when (target) {
        is ReceiptTarget.Item ->
            invoiceReceiptQueryPort.findAssignableForItem(target.itemId, pageIndex, pageSize, filter, excludeReceiptIds)
        is ReceiptTarget.Invoice -> invoiceReceiptQueryPort.findAssignableForInvoice(target.invoiceId, pageIndex, pageSize, filter)
    }

    internal fun countAssignable(filter: String?, excludeReceiptIds: Set<Long> = emptySet()): Long = when (target) {
        is ReceiptTarget.Item -> invoiceReceiptQueryPort.countAssignableForItem(target.itemId, filter, excludeReceiptIds)
        is ReceiptTarget.Invoice -> invoiceReceiptQueryPort.countAssignableForInvoice(target.invoiceId, filter)
    }

    /** Pass-through to [InvoiceReceiptQueryPort.findUsageSummaries] — not target-bound itself, but
     *  only the pool tab (which already talks to this gateway for everything else target-bound)
     *  calls it. */
    internal fun findUsageSummaries(receiptIds: List<Long>, unusedText: String, invoiceLevelLabel: String): Map<Long, String> =
        invoiceReceiptQueryPort.findUsageSummaries(receiptIds, unusedText, invoiceLevelLabel)

    /**
     * Every receipt already attached to this invoice, in either scope (`invoice_receipt`,
     * or any of its positions' `invoice_item_receipt`), keyed to a human-readable location label.
     * Reuses the same two complete (unpaged) reads [InvoiceDetailView.renderReceiptsTab]'s Belege
     * tab already merges into its "attached at invoice level" / "attached per position" cards
     * ([ReceiptLinkService.findReceiptsForInvoice], [ReceiptLinkService.findItemReceiptsForInvoice])
     * instead of a new query, so the two views can never disagree about what "this invoice's own
     * receipts" means. A receipt used in more than one place within the invoice gets every
     * location joined into one label.
     *
     * Receipts already attached to [itemTarget] itself are left out — the attached grid above
     * already shows them.
     */
    internal fun findInvoiceOwnReceipts(itemTarget: ReceiptTarget.Item): Pair<List<ReceiptRowDto>, Map<Long, String>> {
        val invoiceLevel = invoiceReceiptQueryPort.findReceiptsForInvoice(invoice.id)
        val byItem = invoiceReceiptQueryPort.findItemReceiptsForInvoice(invoice.id)
        val positionIdByItemId = invoice.items.associate { it.id to it.positionId }

        val receiptsById = linkedMapOf<Long, ReceiptRowDto>()
        val locationsById = linkedMapOf<Long, MutableList<String>>()

        fun record(receipt: ReceiptRowDto, location: String) {
            val id = receipt.id
            receiptsById.putIfAbsent(id, receipt)
            locationsById.getOrPut(id) { mutableListOf() }.add(location)
        }

        invoiceLevel.forEach { record(it, tr("invoiceDetail.items.receipts.pool.invoiceLevel")) }
        byItem.forEach { (itemId, receipts) ->
            val position = positionIdByItemId[itemId] ?: return@forEach
            receipts.forEach { record(it, tr("invoiceDetail.items.receipts.pool.position", position)) }
        }

        // The only caller guards on `canWrite`, which for an Item target means itemId is
        // already non-null (same invariant as [findAttached] above); asserted rather than defended.
        val ownItemReceiptIds = byItem[itemTarget.itemId!!].orEmpty().map { it.id }.toSet()
        val rows = receiptsById.values
            .filterNot { it.id in ownItemReceiptIds }
            .sortedWith(receiptNewestFirst)
        val locations = locationsById
            .filterKeys { it !in ownItemReceiptIds }
            .mapValues { it.value.joinToString(", ") }
        return rows to locations
    }

    internal fun unsavedHint(): String = when (target) {
        is ReceiptTarget.Item -> tr("invoiceDetail.items.receipts.unsavedPosition")
        is ReceiptTarget.Invoice -> tr("invoiceDetail.receipts.unsaved")
    }

    /** The upload block's hint text names what the upload gets attached to, so it must
     *  differ by target now that both targets share the same upload block (before this change this key
     *  was only ever rendered for [ReceiptTarget.Invoice], where it wrongly read "...this
     *  position"; splitting it here is the correction, not new copy). */
    internal fun uploadNamingHint(): String = when (target) {
        is ReceiptTarget.Item -> tr("invoiceDetail.items.receipts.uploadNaming")
        is ReceiptTarget.Invoice -> tr("invoiceDetail.receipts.uploadNaming")
    }
}
