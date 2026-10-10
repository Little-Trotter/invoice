package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.component.TextColumnWidth
import de.littletrotter.ui.component.asActionColumn
import de.littletrotter.ui.component.asCompactColumn
import de.littletrotter.ui.component.asNumericColumn
import de.littletrotter.ui.component.asTextColumn
import de.littletrotter.ui.component.SelectionActionBar
import de.littletrotter.ui.component.detachRowButton
import de.littletrotter.ui.component.ellipsizedTextColumn
import de.littletrotter.ui.notification.UiNotifications

private const val RECEIPTS_ERROR_KEY = "invoiceDetail.items.receipts.error"

/**
 * [ReceiptDialog.open]'s attached-tab building, extracted into its own file so
 * `ReceiptDialog.kt` does not grow further — the agent brief's "do not let large classes grow"
 * rule. An extension function on [ReceiptDialog] rather than free-standing parameters: it shares
 * that class's own `target`/`canWrite` state and [ReceiptDialog.gateway] for every
 * target-bound read/write, exactly the visibility [InvoiceDetailView]'s own sibling
 * extension-function files already use for the same reason, instead of threading a dozen values
 * through.
 *
 * Builds the attached grid's columns ([buildAttachedGrid]: file identity, the "Zugeordnet an"
 * column for [ReceiptTarget.Invoice], the row-level UNLINK via [detachRowButton], and — for a
 * position-assigned [ReceiptTarget.Invoice] row — "Zur Position"), its multi-select/selectable-
 * provider wiring, and the [SelectionActionBar] mirroring "Markierte zuordnen" on the pool tab
 * ([buildAttachedSelectionBar]): bulk-detach the whole current selection in one call, executing
 * immediately with an inline undo for exactly one row ([performSingleBarDetach]) and behind a
 * [ConfirmDialog] (no undo afterwards) for two or more ([performBulkBarDetach]), per the spec's
 * confirmation rule. Every write goes through [guardReceiptAction] instead of a local
 * `catch (ex: Exception)` — see that function's own KDoc for why.
 *
 * reloadAttached/reloadInvoiceReceiptsGroup/reloadPool are open's own local closures — the
 * one piece of state this extraction cannot reach via the class itself, since they are genuinely
 * local to that one method call, not class members. [AttachedTabReloads] bundles them so the
 * split-out helpers below don't each need their own three-parameter signature.
 */
private const val DETACH_UNDO_NOTIFICATION_MS = 6000

/** See the file KDoc above. */
private class AttachedTabReloads(
    private val attached: () -> Unit,
    private val invoiceReceiptsGroup: () -> Unit,
    private val pool: () -> Unit,
) {
    fun runAll() {
        attached()
        invoiceReceiptsGroup()
        pool()
    }
}

internal fun ReceiptDialog.buildAttachedTab(
    reloadAttached: () -> Unit,
    reloadInvoiceReceiptsGroup: () -> Unit,
    reloadPool: () -> Unit,
): Pair<DataTable<AttachedReceiptRow>, SelectionActionBar?> {
    val reloads = AttachedTabReloads(reloadAttached, reloadInvoiceReceiptsGroup, reloadPool)
    val attachedGrid = buildAttachedGrid(reloads)
    val attachedBar = buildAttachedSelectionBar(attachedGrid, reloads)
    return attachedGrid to attachedBar
}

/** Re-attaches a just-detached receipt through the same dispatch a fresh assignment would use. */
private fun ReceiptDialog.undoDetachOne(receiptId: Long, reloads: AttachedTabReloads) {
    guardReceiptAction(onFailure = { ex ->
        // The invoice may have become read-only since the detach (e.g. sent in another
        // session/tab): the existing generic error path already resolves that into a readable
        // message instead of a raw stack trace; it must not crash the click.
        log.error("Failed to undo detach of receiptId=$receiptId (${gateway.targetContext()})", ex)
        UiNotifications.error(tr(RECEIPTS_ERROR_KEY, ex.message ?: ""))
    }) {
        gateway.addSelected(listOf(receiptId))
        onChanged()
        reloads.runAll()
    }
}

private fun ReceiptDialog.notifyDetachedWithUndo(fileName: String, receiptId: Long, reloads: AttachedTabReloads) {
    UiNotifications.actionable(
        tr("invoiceDetail.items.receipts.removed", fileName),
        tr("receipt.detach.undo"),
        DETACH_UNDO_NOTIFICATION_MS,
    ) { undoDetachOne(receiptId, reloads) }
}

/** The row-level UNLINK button's click: detaches exactly this one receipt, then offers undo. */
private fun ReceiptDialog.detachOneRow(row: AttachedReceiptRow, reloads: AttachedTabReloads) {
    val receiptId = row.receipt.id
    guardReceiptAction(onFailure = { ex ->
        // Click listener: must not let an exception escape (undefined UI state for the rest of
        // the session), so this stays broad — see guardReceiptAction's own KDoc.
        log.error("Failed to detach receiptId=$receiptId (${gateway.targetContext()})", ex)
        UiNotifications.error(tr(RECEIPTS_ERROR_KEY, ex.message ?: ""))
    }) {
        gateway.removeOne(receiptId)
        onChanged()
        reloads.runAll()
        notifyDetachedWithUndo(row.receipt.fileName, receiptId, reloads)
    }
}

/** A position-assigned row is a dead end no more: this button closes the dialog and opens
 *  the position's own dialog instead of leaving the user to find the item themselves on the
 *  Positionen tab. `null` when the item can no longer be resolved or no navigation was wired. */
private fun ReceiptDialog.goToPositionButton(row: AttachedReceiptRow): Component? {
    val item = row.itemId?.let { id -> invoice.items.find { it.id == id } } ?: return null
    val navigate = onNavigateToItem ?: return null
    return AppButtons.tertiary(tr("receipt.action.goToPosition", item.positionId), VaadinIcon.ARROW_RIGHT) {
        dlg.close()
        navigate(item)
    }.apply { addThemeVariants(ButtonVariant.LUMO_SMALL) }
}

/** fix — a position-assigned row (Invoice target only) never gets a detach button, regardless of
 *  canWrite: it isn't this dialog's link to remove, the position's own dialog owns it; it offers
 *  "Zur Position" instead. A row in this grid is always a persisted receipt (from
 *  findAttached), same invariant downloadAnchor/viewAnchor already assert on. */
private fun ReceiptDialog.attachedRowActions(row: AttachedReceiptRow, reloads: AttachedTabReloads): Component {
    val actions = mutableListOf<Component>(downloadAnchor(row.receipt), viewAnchor(row.receipt))
    if (canWrite && !row.readOnly) {
        // "Detach", not "delete" — the receipt stays in the pool. The old trash icon read as if
        // it destroyed the document.
        actions.add(detachRowButton(tr("receipt.action.detach")) { detachOneRow(row, reloads) })
    } else if (target is ReceiptTarget.Invoice && row.readOnly) {
        goToPositionButton(row)?.let(actions::add)
    }
    return HorizontalLayout(*actions.toTypedArray()).apply { isSpacing = true }
}

private fun ReceiptDialog.buildAttachedGrid(reloads: AttachedTabReloads): DataTable<AttachedReceiptRow> =
    DataTable(AttachedReceiptRow::class.java, multiselect = canWrite).apply {
        setWidthFull()
        // Bounded instead of isAllRowsVisible: the dialog must not grow with the data.
        height = "300px"
        // fix — leading column, Invoice target only; the Item target's columns stay exactly
        // as before (that target never has a read-only row to explain).
        if (target is ReceiptTarget.Invoice) {
            this@buildAttachedGrid.addAssignedToColumn(this)
        }
        ellipsizedTextColumn { it.receipt.fileName }.setHeader(tr("receipt.column.fileName")).asTextColumn(TextColumnWidth.MEDIUM)
        ellipsizedTextColumn { it.receipt.description }.setHeader(tr("receipt.column.description")).asTextColumn(TextColumnWidth.MEDIUM)
        addColumn { formatReceiptDocumentDate(it.receipt.documentDate) }.setHeader(tr("receipt.column.documentDate")).asCompactColumn()
        addColumn { formatReceiptSize(it.receipt.sizeBytes) }.setHeader(tr("receipt.column.size")).asNumericColumn()
        addColumn { formatReceiptUploadedAt(it.receipt.uploadedAt) }.setHeader(tr("receipt.column.uploadedAt")).asCompactColumn()
        // Only a detachable row's checkbox is offered at all: a position-assigned row
        // (Invoice target, read-only) isn't this dialog's link to bulk-detach either.
        setItemSelectableProvider { row -> canWrite && !row.readOnly }
        addComponentColumn { row -> attachedRowActions(row, reloads) }.asActionColumn()
    }

/** The [SelectionActionBar]'s action for exactly one selected row: same immediate-write-plus-undo
 *  shape as [detachOneRow], distinguished only by resetting the grid's own selection afterwards. */
private fun ReceiptDialog.performSingleBarDetach(attachedGrid: DataTable<AttachedReceiptRow>, row: AttachedReceiptRow, reloads: AttachedTabReloads) {
    val receiptId = row.receipt.id
    guardReceiptAction(onFailure = { ex ->
        log.error("Failed to detach receiptIds=[$receiptId] (${gateway.targetContext()})", ex)
        UiNotifications.error(tr(RECEIPTS_ERROR_KEY, ex.message ?: ""))
    }) {
        gateway.removeOne(receiptId)
        onChanged()
        attachedGrid.deselectAll()
        reloads.runAll()
        notifyDetachedWithUndo(row.receipt.fileName, receiptId, reloads)
    }
}

/** The [SelectionActionBar]'s action for two or more selected rows: only reached after the
 *  [ConfirmDialog] below is confirmed; no undo afterwards, per the spec's confirmation rule. */
private fun ReceiptDialog.performBulkBarDetach(attachedGrid: DataTable<AttachedReceiptRow>, ids: List<Long>, reloads: AttachedTabReloads) {
    ConfirmDialog(
        tr("receipt.detach.confirm.title"),
        tr("receipt.detach.confirm.invoice", ids.size.toString()),
        tr("receipt.action.detachSelected"),
    ) {
        guardReceiptAction(onFailure = { ex ->
            log.error("Failed to bulk-detach receiptIds=$ids (${gateway.targetContext()})", ex)
            UiNotifications.error(tr(RECEIPTS_ERROR_KEY, ex.message ?: ""))
        }) {
            gateway.removeMany(ids)
            onChanged()
            attachedGrid.deselectAll()
            reloads.runAll()
            UiNotifications.success(tr("receipt.detach.doneMany", ids.size.toString()))
        }
    }.apply {
        setCancelable(true)
        setCancelText(tr("app.cancel"))
        open()
    }
}

private fun ReceiptDialog.performBarDetach(attachedGrid: DataTable<AttachedReceiptRow>, reloads: AttachedTabReloads) {
    val selected = attachedGrid.selectedItems.toList()
    val ids = selected.map { it.receipt.id }
    when {
        ids.isEmpty() -> return
        ids.size == 1 -> performSingleBarDetach(attachedGrid, selected.single(), reloads)
        else -> performBulkBarDetach(attachedGrid, ids, reloads)
    }
}

private fun ReceiptDialog.buildAttachedSelectionBar(
    attachedGrid: DataTable<AttachedReceiptRow>,
    reloads: AttachedTabReloads,
): SelectionActionBar? {
    if (!canWrite) return null
    val bar = SelectionActionBar(
        countText = { n -> tr("receipt.selection.count", n.toString()) },
        actionText = tr("receipt.action.detachSelected"),
        actionIcon = VaadinIcon.UNLINK,
        clearAriaLabel = tr("receipt.selection.clear"),
        onClear = { attachedGrid.deselectAll() },
        onAction = { performBarDetach(attachedGrid, reloads) },
    ).apply { isVisible = false }
    attachedGrid.addSelectionListener {
        bar.isVisible = attachedGrid.selectedItems.isNotEmpty()
        bar.setCount(attachedGrid.selectedItems.size)
    }
    return bar
}
