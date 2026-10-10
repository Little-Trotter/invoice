package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.ModalityMode
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.tabs.Tab
import com.vaadin.flow.component.tabs.TabSheet
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.dto.ReceiptRowDto
import de.littletrotter.application.invoice.ReceiptCountLimitExceededException
import de.littletrotter.application.invoice.ReceiptUploadMode
import de.littletrotter.application.invoice.port.inbound.AttachReceiptsUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceReceiptQueryPort
import de.littletrotter.shared.receipt.InvoiceDocumentBudget
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.AppDialog
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.notification.UiNotifications
import java.time.LocalDate
import org.jboss.logging.Logger
import de.littletrotter.ui.component.ActionIcons

/**
 * Receipts of one [ReceiptTarget] (see [ReceiptDialogLauncher]) as a self-contained component,
 * opened and reloaded by [ReceiptDialogLauncher] rather than reaching into `InvoiceDetailView`
 * directly.
 *
 * Three jobs live here: **managing** what is already attached (view, download, detach),
 * **picking** more from the pool (search, multiselect, assign), and **uploading** a new file
 * straight into the pool and onto this target in one step. Both targets now present these
 * three jobs the same way, as tabs (Zugeordnet / Belegpool / Hochladen): the earlier
 * single-stacked-view layout for [ReceiptTarget.Item] hid the pool picker and upload block below
 * the fold whenever a position already carried a few receipts, which is exactly the ergonomic
 * complaint that motivated this pass.
 *
 * The tab bar itself carries live state rather than a static label — the attached tab's count and
 * the pool tab's remaining-slots hint (see [ReceiptDialog.open]'s `updateAttachedTabLabel`/
 * `updatePoolTabLabel`) update after every write, so the cap and what's already attached are both
 * visible without opening a tab. A successful upload or assignment lands the user on the attached
 * tab with the affected row selected — the result of the action, not an empty form, is what's on
 * screen afterwards.
 *
 * Every action here writes through immediately — there is no draft state to discard. The footer
 * therefore closes, it does not cancel. [onChanged] is called after every successful write so the
 * caller can refresh whatever it is that changed (the item grid AND the Belege tab for
 * [ReceiptTarget.Item], the Belege tab for [ReceiptTarget.Invoice]) — this dialog doesn't know
 * which.
 *
 * This class now only holds its own construction/lifecycle logic (ctor state, [open],
 * [buildTab], the `OpenSession` orchestrating attached-tab/pool-tab/upload-tab assembly); the
 * target-bound repository/service calls live in [gateway] ([ReceiptTargetGateway]), the shared
 * anchor/column rendering in `ReceiptDialogRendering.kt`, and the "Belegpool" tab in the
 * [ReceiptPoolPane] composer — split out because this file had grown past 900 lines.
 */

/** [ReceiptDialog.OpenSession.handleUpload]'s own picked-file bundle — carries the four values
 *  [ReceiptDialog.OpenSession.performUpload] needs from pick time through to the (possibly
 *  deferred, if [offerReceiptUpload] opens the reduce dialog) upload-confirm time, keeping
 *  `performUpload` itself under detekt's `LongParameterList` threshold. Not a `data class`:
 *  [bytes] is a `ByteArray` (detekt `ArrayInDataClass`), same reasoning as `ReceiptUploadService
 *  .ReducedPayload`. */
private class PendingReceiptUpload(
    val fileName: String,
    val bytes: ByteArray,
    val description: String,
    val documentDate: LocalDate?,
)

internal class ReceiptDialog(
    // Replaces the deleted receipt-services bundle: reads through
    // [invoiceReceiptQueryPort], writes through [attachReceiptsUseCase]. Both are `internal` (not
    // private), same reasoning as before — ReceiptDialogRendering.kt's extension function reads
    // [invoiceReceiptQueryPort] directly for [ReceiptDialogRendering.receiptDownloadResponse].
    internal val invoiceReceiptQueryPort: InvoiceReceiptQueryPort,
    internal val attachReceiptsUseCase: AttachReceiptsUseCase,
    // Internal (not private): ReceiptDialogAttachedTab.kt's extension function and
    // ReceiptPoolPane need these, extracted out of [open] so this file does not grow further
    // (agent brief: "do not let large classes grow" — see that file's own KDoc).
    internal val invoice: InvoiceDetailDto,
    internal val target: ReceiptTarget,
    internal val onChanged: () -> Unit,
    /** Invoked (after this dialog has closed itself) for a position-assigned row's "Zur
      *  Position" button, which only [ReceiptTarget.Invoice]'s attached grid ever renders; `null`
      *  (every other construction site, including every existing [ReceiptTarget.Item] dialog) means
      *  that button is simply never built. */
    internal val onNavigateToItem: ((ItemDto) -> Unit)? = null,
) : TranslationProvider {
    internal val log = Logger.getLogger(javaClass)
    internal val dlg = AppDialog("", AppDialog.Size.L)
    override fun getComponent(): Component = dlg

    private val readOnly = invoice.isReadOnly()
    // A target without an id cannot be written to; guard instead of throwing later.
    internal val unsaved = when (target) {
        is ReceiptTarget.Item -> target.itemId == null
        is ReceiptTarget.Invoice -> target.invoiceId == null
    }
    internal val canWrite = !readOnly && !unsaved

    /** Every target-bound repository/service call; see [ReceiptTargetGateway]'s own KDoc. */
    internal val gateway = ReceiptTargetGateway(invoiceReceiptQueryPort, attachReceiptsUseCase, invoice, target, dlg)

    /**
     * One tab of the dialog's tab bar: an icon (decorative, [ActionIcons.ATTACHMENTS] for
     * attached / [VaadinIcon.ARCHIVES] for the pool / [VaadinIcon.UPLOAD] for upload — the same
     * icons the rest of the app already uses for these exact concepts, see the item grid's own
     * paperclip button and the "Belege" navigation entry) next to a live-updatable label. The
     * label is a [Span], not [Tab.setLabel]: `Tab.setLabel` replaces the tab's whole text content
     * (`Element.setText`), which would silently delete the icon on the first live update.
     * Returns the tab together with that [Span] so the caller can mutate its text later without
     * touching the icon.
     */
    private fun buildTab(icon: VaadinIcon, initialText: String): Pair<Tab, Span> {
        val iconComponent = icon.create().apply {
            // Decorative only — the label text already carries the tab's accessible name.
            element.setAttribute("aria-hidden", "true")
        }
        val label = Span(initialText)
        val header = HorizontalLayout(iconComponent, label).apply {
            isPadding = false; isSpacing = true
            alignItems = FlexComponent.Alignment.CENTER
        }
        return Tab(header) to label
    }

    fun open() {
        dlg.headerTitle = tr(target.titleKey)
        OpenSession().build()
    }

    /**
     * S3776 — [open] assembled the whole dialog (attached tab, pool tab, upload tab, live tab
     * labels, the invoice-wide budget) as one function, its many small pieces of UI-building state
     * (grids, the budget) threaded through as forward-declared local closures because a local
     * `var`/`fun` cannot forward-reference another declared later and Kotlin local variables
     * cannot be `lateinit`. As a private inner class, that state becomes ordinary (`lateinit`-able)
     * properties and the closures become ordinary methods callable in any order, so each job below
     * is its own small function instead of one function with all of them inlined. One
     * [OpenSession] per [open] call, matching the original's one-set-of-locals-per-call lifetime;
     * nothing here is reused across dialog opens.
     */
    private inner class OpenSession {
        private lateinit var attachedGrid: DataTable<AttachedReceiptRow>
        private lateinit var attachedPane: VerticalLayout
        private lateinit var attachedLabel: Span
        private var attachedCount = 0

        /** The "Belegpool" tab; see [ReceiptPoolPane]'s own KDoc. */
        private lateinit var poolPane: ReceiptPoolPane

        /** The "Hochladen" tab — built exactly when [canWrite], `null` for a read-only/unsaved target. */
        private var uploadForm: ReceiptUploadForm? = null

        // Read by [handleUpload]'s own pre-check; refreshed by [refreshLimitState].
        private lateinit var budget: InvoiceDocumentBudget

        private lateinit var tabs: TabSheet
        // The pool tab's own live label; stays null (a real no-op for [updatePoolTabLabel])
        // for a read-only/unsaved target, since the pool tab itself is only added to [tabs] when
        // [canWrite].
        private var poolLabel: Span? = null

        fun build() {
            buildAttachedSection()
            poolPane = ReceiptPoolPane(
                this@ReceiptDialog,
                onChanged = this@ReceiptDialog.onChanged,
                reloadAttached = { ids -> reloadAttached(justAddedIds = ids) },
                selectAttachedTab = { selectAttachedTab() },
            )
            uploadForm = if (canWrite) ReceiptUploadForm(gateway.uploadNamingHint(), ::handleUpload) else null
            refreshLimitState()
            assemble()
        }

        // ── Tab 1: already attached ───────────────────────────────────────────────

        private fun buildAttachedSection() {
            // Grid + selection bar + row/bulk detach: see ReceiptDialogAttachedTab.kt
            // (extracted so this file does not grow further).
            val (builtAttachedGrid, attachedBar) = buildAttachedTab(
                reloadAttached = { reloadAttached() },
                reloadInvoiceReceiptsGroup = { poolPane.reloadAfterExternalChange() },
                reloadPool = { poolPane.reloadAfterExternalChange() },
            )
            attachedGrid = builtAttachedGrid

            val attachedInitial = if (unsaved) emptyList() else gateway.findAttached()
            attachedCount = attachedInitial.size
            attachedGrid.setItems(attachedInitial)

            attachedPane = VerticalLayout().apply {
                isPadding = false; isSpacing = true
                if (unsaved) {
                    add(Span(gateway.unsavedHint()).apply { addClassName("text-muted") })
                }
                attachedBar?.let { add(it) }
                add(attachedGrid)
            }
        }

        private fun attachedTabLabel(): String = tr("invoiceDetail.items.receipts.tab.attached", attachedCount.toString())

        private fun selectAttachedTab() {
            tabs.selectedIndex = 0
        }

        private fun updateAttachedTabLabel() {
            attachedLabel.text = attachedTabLabel()
        }

        // Every caller of this function is wired behind a canWrite-gated control (the
        // detach button, the assign button's post-guard success/limit paths, the upload callback),
        // and canWrite implies !unsaved, so this can only ever run with unsaved already false.
        //
        // [justAddedIds] names the receipts a write just attached, so the caller can land
        // the user on a result they can actually see instead of an unchanged-looking grid: the
        // matching row(s) come out selected once the fresh data is in. Empty for a detach (nothing
        // new to point at) and for the failure/limit paths (nothing was actually attached).
        private fun reloadAttached(justAddedIds: Set<Long> = emptySet()) {
            val rows = gateway.findAttached()
            attachedCount = rows.size
            attachedGrid.setItems(rows)
            updateAttachedTabLabel()
            updatePoolTabLabel()
            refreshLimitState()
            if (justAddedIds.isNotEmpty()) {
                rows.lastOrNull { it.receipt.id in justAddedIds }?.let { attachedGrid.select(it) }
            }
        }

        private fun updatePoolTabLabel() {
            poolLabel?.let { it.text = poolPane.tabLabelText() }
        }

        // ── upload ─────────────────────────────────────────────────────────────────
        // Reused verbatim by both targets: [ReceiptTargetGateway.addSelected] already
        // dispatches to addReceiptsToItem/addReceiptsToInvoice by target, so the upload closure
        // itself needs no target branching. Gated on [canWrite] (was: ReceiptTarget.Invoice only)
        // — the block must not be offered at all on a read-only/unsaved target, not merely fail
        // after a pick ([ReceiptUploadForm] is only built when [canWrite]). A free slot is
        // already required to enable the upload control ([refreshLimitState]); the estimated
        // document size is a warning (the budget banner), not a link-time gate — the hard stop is
        // `ReceiptSizePolicy`'s own upload-time caps, applied by [offerReceiptUpload] below.
        // UI event boundary — upload completion callback must not let ANY exception through, sole
        // catch-all of this flow.
        private fun handleUpload(fileName: String, bytes: ByteArray, description: String, documentDate: LocalDate?) {
            val pending = PendingReceiptUpload(fileName, bytes, description, documentDate)
            offerReceiptUpload(dlg, bytes) { mode -> performUpload(pending, mode) }
        }

        private fun performUpload(pending: PendingReceiptUpload, mode: ReceiptUploadMode) {
            // The storage write and the attach run off-lock; everything the user sees (reloads,
            // notifications, tab switch) follows in the short access once both have committed.
            runReceiptUploadOffLock(
                dlg,
                work = {
                    val receipt = attachReceiptsUseCase.upload(pending.fileName, pending.description, pending.bytes, pending.documentDate, mode)
                    // upload() always returns a persisted receipt (existing, re-checked, or
                    // freshly saved — every return path has gone through the repository).
                    gateway.addSelected(listOf(receipt.id))
                    receipt
                },
            ) { outcome -> reportUpload(pending, outcome) }
        }

        private fun showUploaded(receipt: ReceiptRowDto) {
            onChanged()
            uploadForm?.clear()
            reloadAttached(justAddedIds = setOfNotNull(receipt.id))
            poolPane.reloadAfterExternalChange()
            showReceiptUploadResult(
                dlg,
                ReceiptUploadOutcome(receipt.fileName, receipt.normalisationStage, receipt.originalSizeBytes, receipt.sizeBytes),
            )
            // Same landing rule as assigning — show the target's receipts, not an empty form.
            selectAttachedTab()
        }

        private fun reportUpload(pending: PendingReceiptUpload, outcome: Result<ReceiptRowDto>) {
            try {
                showUploaded(outcome.getOrThrow())
            } catch (e: ReceiptCountLimitExceededException) {
                // The file is in the pool now (the upload itself succeeded) but was not attached —
                // say exactly that instead of a bare "error". Same wording for both targets — this
                // whole block is shared, not duplicated per target.
                UiNotifications.error(tr("receipt.error.limitExceededUpload", e.limit.toString()))
                reloadAttached()
                poolPane.reloadAfterExternalChange()
            } catch (e: Exception) {
                // Upload completion callback: runs off Vaadin's upload machinery like a
                // listener does, so an escaping exception has the same undefined-state risk.
                // ReceiptReductionFailedException/ReceiptUnreducedTooLargeException/
                // ReceiptUploadTooLargeException each get their own named wording
                // (receiptUploadExceptionMessage); anything else falls back to the shared generic
                // one, logged with the uploaded file name so it stays discoverable.
                val specific = receiptUploadExceptionMessage(dlg, e)
                if (specific != null) {
                    UiNotifications.error(specific)
                } else {
                    log.error("Failed to process uploaded receipt '${pending.fileName}' (${gateway.targetContext()})", e)
                    UiNotifications.error(tr("invoiceDetail.items.receipts.error", e.message ?: ""))
                }
            }
        }

        // ── limit state ───────────────────────────────────────────────────────────
        // Touches components shared across the layout (the pool pane and the upload field where
        // it exists) — all must be off once the cap is reached; uploading into a full target would
        // create a pool entry that cannot be attached.
        private fun refreshLimitState() {
            budget = invoiceReceiptQueryPort.invoiceDocumentBudget(invoice.id)
            val assignedObjectKeys = if (canWrite) {
                (invoiceReceiptQueryPort.findReceiptsForInvoice(invoice.id) +
                    invoiceReceiptQueryPort.findItemReceiptsForInvoice(invoice.id).values.flatten())
                    .map { it.objectKey }.toSet()
            } else {
                emptySet()
            }
            poolPane.refresh(budget, assignedObjectKeys)
            // Count only — the estimated size is a warning (the budget banner), not a link-time
            // gate; see ReceiptPoolPane.updateAssignButtonState's identical count-only rule.
            uploadForm?.setBudgetAllowsUpload(budget.remainingSlots > 0)
            updatePoolTabLabel()
        }

        // ── assembly ──────────────────────────────────────────────────────────────
        // One tab bar for both targets: attached / Belegpool / Hochladen. Was a `when
        // (target)` split (Invoice tabbed, Item a single stacked view) — the position dialog now
        // mirrors the invoice dialog exactly, so this builds once instead of twice. Nothing here
        // branches on [target] any more; the genuinely target-specific part (this-invoice
        // grouping) already lives inside [poolPane] itself, built above regardless of which target
        // this is.
        private fun assemble() {
            tabs = TabSheet().apply { setWidthFull() }
            val (attachedTab, builtAttachedLabel) = buildTab(ActionIcons.ATTACHMENTS, attachedTabLabel())
            attachedLabel = builtAttachedLabel
            tabs.add(attachedTab, attachedPane)
            if (canWrite) {
                val (poolTab, builtPoolLabel) = buildTab(VaadinIcon.ARCHIVES, poolPane.tabLabelText())
                poolLabel = builtPoolLabel
                tabs.add(poolTab, poolPane.getComponent())
                // uploadForm is built exactly when canWrite (above), so it is non-null
                // whenever this branch runs; the nullable *type* stays (uploadForm is legitimately
                // absent for a read-only/unsaved target), only this proven-safe read is asserted.
                val (uploadTab, _) = buildTab(VaadinIcon.UPLOAD, tr("invoiceDetail.items.receipts.tab.upload"))
                tabs.add(uploadTab, uploadForm!!.getComponent())
            }
            dlg.add(tabs)

            // Everything in this dialog is written through immediately; there is nothing to cancel.
            // Labelling the only way out "Abbrechen" suggested the assignments would be rolled back.
            dlg.footer(AppButtons.tertiary(tr("app.close")) { dlg.close() })
            dlg.modality = ModalityMode.STRICT
            dlg.isCloseOnOutsideClick = true
            dlg.open()
        }
    }
}
