package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ReceiptDetachSelection
import de.littletrotter.application.dto.ReceiptRowDto
import de.littletrotter.shared.receipt.InvoiceDocumentBudget
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.SelectionActionBar
import de.littletrotter.ui.notification.UiNotifications

private const val DETACH_UNDO_NOTIFICATION_MS = 6000

/**
 * [InvoiceReceiptsTab]'s detach/undo ergonomics, extracted into their own file so that class does
 * not grow further — same "do not let large classes grow" rule as [ReceiptDialog]'s own sibling
 * extension files ([ReceiptDialogAttachedTab.kt]/[ReceiptDialogRendering.kt]). Extension functions
 * on [InvoiceReceiptsTab], not free-standing parameters: they share that class's own
 * `current`/`invoiceReceiptQueryPort`/`attachReceiptsUseCase`/`receiptDialogLauncher`/`log` state.
 *
 * The one detached link a single-row (or one-item bar) detach might still need to undo,
 * remembered only for the lifetime of that detach's own undo notification.
 */
internal sealed class TabDetachedReceipt(val fileName: String) {
    class InvoiceLevel(val receiptId: Long, fileName: String) : TabDetachedReceipt(fileName)
    class OnItem(val itemId: Long, val receiptId: Long, fileName: String) : TabDetachedReceipt(fileName)
}

/** Single-row detach (row-level UNLINK button), invoice-level scope. */
internal fun InvoiceReceiptsTab.detachInvoiceLevelReceipt(receipt: ReceiptRowDto) {
    executeTabDetach(
        current,
        ReceiptDetachSelection(invoiceLevelReceiptIds = setOf(receipt.id)),
        TabDetachedReceipt.InvoiceLevel(receipt.id, receipt.fileName),
    )
}

/** Single-row detach (row-level UNLINK button), position scope. */
internal fun InvoiceReceiptsTab.detachPositionReceipt(row: InvoiceReceiptsTab.PositionReceiptRow) {
    executeTabDetach(
        current,
        ReceiptDetachSelection(itemReceiptIds = mapOf(row.item.id to setOf(row.receipt.id))),
        TabDetachedReceipt.OnItem(row.item.id, row.receipt.id, row.receipt.fileName),
    )
}

/** [SelectionActionBar]'s action for exactly one row selected (across both tables combined): same
 *  immediate-write-plus-undo shape as a row's own detach button. */
internal fun InvoiceReceiptsTab.performSingleTabDetach(
    invoiceGrid: DataTable<ReceiptRowDto>,
    positionGrid: DataTable<InvoiceReceiptsTab.PositionReceiptRow>,
    invoiceIds: Set<Long>,
    itemGroups: Map<Long, Set<Long>>,
) {
    val undo = if (invoiceIds.isNotEmpty()) {
        val r = invoiceGrid.selectedItems.first()
        TabDetachedReceipt.InvoiceLevel(r.id, r.fileName)
    } else {
        val row = positionGrid.selectedItems.first()
        TabDetachedReceipt.OnItem(row.item.id, row.receipt.id, row.receipt.fileName)
    }
    executeTabDetach(current, ReceiptDetachSelection(invoiceIds, itemGroups), undo)
}

/** [SelectionActionBar]'s action for two or more rows selected (across both tables combined): only
 *  reached after the [ConfirmDialog] below is confirmed; no undo afterwards, per the spec's
 *  confirmation rule. */
internal fun InvoiceReceiptsTab.performBulkTabDetach(
    invoiceIds: Set<Long>,
    itemGroups: Map<Long, Set<Long>>,
    total: Int,
) {
    val invoiceForDialog = current
    val text = if (itemGroups.isNotEmpty()) {
        tr("receipt.detach.confirm.positions", total.toString(), itemGroups.keys.size.toString())
    } else {
        tr("receipt.detach.confirm.invoice", total.toString())
    }
    ConfirmDialog(
        tr("receipt.detach.confirm.title"),
        text,
        tr("receipt.action.detachSelected"),
    ) { executeTabDetachMany(invoiceForDialog, ReceiptDetachSelection(invoiceIds, itemGroups), total) }.apply {
        setCancelable(true)
        setCancelText(tr("app.cancel"))
        open()
    }
}

/** The two tables' selections combine into exactly one atomic
 *  [de.littletrotter.application.invoice.ReceiptLinkService.detachReceipts] call — a bulk
 *  selection spanning positions and the invoice level at once must still be one atomic write. */
internal fun InvoiceReceiptsTab.performTabDetach(
    invoiceGrid: DataTable<ReceiptRowDto>,
    positionGrid: DataTable<InvoiceReceiptsTab.PositionReceiptRow>,
) {
    val invoiceIds = invoiceGrid.selectedItems.map { it.id }.toSet()
    val itemGroups: Map<Long, Set<Long>> = positionGrid.selectedItems
        .groupBy({ it.item.id }, { it.receipt.id })
        .mapValues { (_, ids) -> ids.toSet() }
    val total = invoiceIds.size + itemGroups.values.sumOf { it.size }
    when (total) {
        0 -> return
        1 -> performSingleTabDetach(invoiceGrid, positionGrid, invoiceIds, itemGroups)
        else -> performBulkTabDetach(invoiceIds, itemGroups, total)
    }
}

/** Bundles the two tables' "normal" (non-selection) headers — needed in both the read-only and the
 *  writable branch of [InvoiceReceiptsTab.render]. */
internal class ReceiptsTabHeaders(val invoiceNormalHeader: Div, val positionNormalHeader: Div)

internal fun InvoiceReceiptsTab.buildReceiptsTabHeaders(budget: InvoiceDocumentBudget): ReceiptsTabHeaders {
    val invoiceHeading = H2(tr("invoiceDetail.receipts.heading"))
    val invoiceHint = Span().apply {
        addClassName("fs-08")
        applyBudgetHint(budgetHint(this@buildReceiptsTabHeaders, budget))
    }
    val positionHeading = H2(tr("invoiceDetail.receipts.byPosition.heading"))
    return ReceiptsTabHeaders(
        invoiceNormalHeader = Div(invoiceHeading, invoiceHint),
        positionNormalHeader = Div(positionHeading),
    )
}

/** A tab's table paired with the card it renders into — keeps the pairing as one value instead of
 *  a long parameter list; the writable branch always builds and wires the two
 *  ([InvoiceReceiptsTab.wireReceiptsTabSelection]'s [invoice]/[position] arguments) as matching
 *  units, never a grid without its own card. */
internal class ReceiptTableSection<T : Any>(val grid: DataTable<T>, val card: Div)

/** Wires the writable case's Material "contextual app bar" for both tables: a selection in either
 *  table hides that table's normal header and shows its bar with the COMBINED count (both tables
 *  clear and detach together, see [InvoiceReceiptsTab.performTabDetach]). */
internal fun InvoiceReceiptsTab.wireReceiptsTabSelection(
    invoice: ReceiptTableSection<ReceiptRowDto>,
    position: ReceiptTableSection<InvoiceReceiptsTab.PositionReceiptRow>,
    headers: ReceiptsTabHeaders,
) {
    val invoiceGrid = invoice.grid
    val positionGrid = position.grid
    lateinit var invoiceBar: SelectionActionBar
    lateinit var positionBar: SelectionActionBar

    fun refreshSelectionUi() {
        val combined = invoiceGrid.selectedItems.size + positionGrid.selectedItems.size
        val invoiceHasSelection = invoiceGrid.selectedItems.isNotEmpty()
        val positionHasSelection = positionGrid.selectedItems.isNotEmpty()
        headers.invoiceNormalHeader.isVisible = !invoiceHasSelection
        invoiceBar.isVisible = invoiceHasSelection
        invoiceBar.setCount(combined)
        headers.positionNormalHeader.isVisible = !positionHasSelection
        positionBar.isVisible = positionHasSelection
        positionBar.setCount(combined)
    }

    fun clearTabSelection() {
        invoiceGrid.deselectAll()
        positionGrid.deselectAll()
        refreshSelectionUi()
    }

    fun detachBar(): SelectionActionBar = SelectionActionBar(
        countText = { n -> tr("receipt.selection.count", n.toString()) },
        actionText = tr("receipt.action.detachSelected"),
        actionIcon = VaadinIcon.UNLINK,
        clearAriaLabel = tr("receipt.selection.clear"),
        onClear = { clearTabSelection() },
        onAction = { performTabDetach(invoiceGrid, positionGrid) },
    ).apply { isVisible = false }

    invoiceBar = detachBar()
    positionBar = detachBar()

    invoiceGrid.addSelectionListener { refreshSelectionUi() }
    positionGrid.addSelectionListener { refreshSelectionUi() }

    invoice.card.add(headers.invoiceNormalHeader, invoiceBar, invoiceGrid)
    position.card.add(headers.positionNormalHeader, positionBar, positionGrid)
}

/** Single detach (a row button, or a bar action with exactly one row selected): executes
 *  immediately (no modal, see the spec's confirmation rule), then offers an inline undo instead of
 *  asking first. */
internal fun InvoiceReceiptsTab.executeTabDetach(
    invoice: InvoiceDetailDto,
    selection: ReceiptDetachSelection,
    undo: TabDetachedReceipt,
) {
    guardReceiptAction(onFailure = { ex ->
        // Click listener: must not let an exception escape (undefined UI state for the rest of
        // the session). Logged with the invoice id so a failed detach is discoverable.
        log.error("Failed to detach a receipt from invoiceId=${invoice.id}", ex)
        UiNotifications.error(this, ex)
    }) {
        attachReceiptsUseCase.detachReceipts(invoice.id, selection, invoice.version)
        receiptDialogLauncher.reloadAndNotify(invoice.id)
        UiNotifications.actionable(
            tr("invoiceDetail.items.receipts.removed", undo.fileName),
            tr("receipt.detach.undo"),
            DETACH_UNDO_NOTIFICATION_MS,
        ) { undoTabDetach(invoice.id, undo) }
    }
}

/** Bulk detach (a bar action with two or more rows selected): only reached after the
 *  [ConfirmDialog] the caller already showed; no undo afterwards, per the spec's confirmation
 *  rule. */
internal fun InvoiceReceiptsTab.executeTabDetachMany(
    invoice: InvoiceDetailDto,
    selection: ReceiptDetachSelection,
    total: Int,
) {
    guardReceiptAction(onFailure = { ex ->
        log.error("Failed to bulk-detach receipts from invoiceId=${invoice.id}", ex)
        UiNotifications.error(this, ex)
    }) {
        attachReceiptsUseCase.detachReceipts(invoice.id, selection, invoice.version)
        receiptDialogLauncher.reloadAndNotify(invoice.id)
        UiNotifications.success(tr("receipt.detach.doneMany", total.toString()))
    }
}

/** Re-assigns through the same [de.littletrotter.application.invoice.ReceiptLinkService] entry
 *  points a fresh assignment would use, with the invoice version this tab holds *now* (not the one
 *  captured when the original detach ran) — a write elsewhere in between is exactly what the
 *  optimistic-lock/read-only guards below must still catch instead of crashing. */
internal fun InvoiceReceiptsTab.undoTabDetach(invoiceId: Long, detached: TabDetachedReceipt) {
    val version = current.version
    guardReceiptAction(onFailure = { ex ->
        // The invoice may have become read-only since the detach (e.g. sent in another
        // session/tab): the existing generic error path already resolves that into a readable
        // message instead of a raw stack trace; it must not crash the click.
        log.error("Failed to undo a receipt detach on invoiceId=$invoiceId", ex)
        UiNotifications.error(this, ex)
    }) {
        when (detached) {
            is TabDetachedReceipt.InvoiceLevel ->
                attachReceiptsUseCase.addReceiptsToInvoice(invoiceId, listOf(detached.receiptId), version)
            is TabDetachedReceipt.OnItem ->
                attachReceiptsUseCase.addReceiptsToItem(detached.itemId, listOf(detached.receiptId), version)
        }
        receiptDialogLauncher.reloadAndNotify(invoiceId)
    }
}
