package de.littletrotter.ui.view.invoice

import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.ui.view.isConcurrencyConflict

/**
 * Holds the state of the currently opened invoice that used to live directly on
 * [InvoiceDetailView]: the loaded [InvoiceDetailDto], whether the overview tab shows the entry
 * (edit) pane, and the "invoice changed" decision that used to be [InvoiceDetailView]'s own
 * `refreshAfterDataChange`/`renderInvoiceOrDeferIfEditing`/`pendingRefreshAfterEdit` trio: while
 * [editMode] is on, an incoming change is only recorded via [pendingChange] instead of rendered
 * immediately, so a save triggered from another tab can't silently discard unsaved entry input
 * (K0 minimal cross-tab guard).
 *
 * Also the hand-off for the two callbacks the Versionen-tab panel used to receive as
 * constructor lambdas ([onValidationFailure]/[onPrintScheduled]): now that the panel is a
 * CDI-injected bean shared for the view's whole lifetime rather than a lambda-capturing object
 * rebuilt per invoice, it reaches the view's reaction to those two events through this state
 * object (bound once via `InvoiceExportPanel.bind`) instead of through per-construction closures.
 *
 * Not a CDI bean and not a dependency bundle — [InvoiceDetailView] owns exactly one instance per
 * opened invoice and keeps exposing [dto]/[editMode]/[pendingChange] through its own unchanged
 * `invoice`/`overviewEditMode`/`pendingRefreshAfterEdit` properties, so no extension-function
 * file or Karibu test outside this file and [InvoiceDetailView] needs to change.
 */
internal class InvoiceDetailState {
    /** The currently loaded invoice, or null before the first successful load. */
    var dto: InvoiceDetailDto? = null

    /** True while the overview tab shows the entry (edit) pane instead of the read-only document pane. */
    var editMode: Boolean = false

    /** Set when [applyChange] was deferred because [editMode] was on; consumed by [exitEditMode]. */
    var pendingChange: Boolean = false

    private var listener: ((InvoiceDetailDto) -> Unit)? = null
    private var validationFailureListener: ((InvoiceValidationException) -> Unit)? = null
    private var printScheduledListener: (() -> Unit)? = null
    private var receiptsChangedListener: ((InvoiceDetailDto) -> Unit)? = null
    private var reloadListener: (() -> Unit)? = null

    /** Registers the single callback [applyChange] invokes for a change that is not deferred. */
    fun onInvoiceChanged(callback: (InvoiceDetailDto) -> Unit) {
        listener = callback
    }

    /** Registers the callback [reloadAfterConflict] invokes — the view re-reads the committed
     *  invoice and leaves entry mode so the user sees what the other session saved. */
    fun onReloadRequested(callback: () -> Unit) {
        reloadListener = callback
    }

    /** Called after a concurrency conflict; a no-op when nothing registered [onReloadRequested]. */
    fun reloadAfterConflict() {
        reloadListener?.invoke()
    }

    /** Registers the callback [notifyReceiptsChanged] invokes — the Belege-tab component or the
     *  receipt dialog wrote a change and reloaded the committed invoice. */
    fun onReceiptsChanged(callback: (InvoiceDetailDto) -> Unit) {
        receiptsChangedListener = callback
    }

    /** Registers the callback [notifyValidationFailure] invokes — the Versionen-tab panel's
     *  print-job creation failed invoice validation. */
    fun onValidationFailure(callback: (InvoiceValidationException) -> Unit) {
        validationFailureListener = callback
    }

    /** Registers the callback [notifyPrintScheduled] invokes — the Versionen-tab panel scheduled
     *  a print job successfully. */
    fun onPrintScheduled(callback: () -> Unit) {
        printScheduledListener = callback
    }

    /** Called by the Versionen-tab panel; a no-op when nothing registered [onValidationFailure]. */
    fun notifyValidationFailure(exception: InvoiceValidationException) {
        validationFailureListener?.invoke(exception)
    }

    /** Called by the Versionen-tab panel; a no-op when nothing registered [onPrintScheduled]. */
    fun notifyPrintScheduled() {
        printScheduledListener?.invoke()
    }

    /** Called by the Belege-tab component or the receipt dialog launcher after a write reloaded
     *  the committed invoice; a no-op when nothing registered [onReceiptsChanged]. Takes the
     *  already-reloaded [InvoiceDetailDto] rather than an id — the caller reads through the same
     *  input port the view itself would, so the view no longer needs its own reload step. */
    fun notifyReceiptsChanged(current: InvoiceDetailDto) {
        receiptsChangedListener?.invoke(current)
    }

    /**
     * Applies an incoming data change immediately, unless [editMode] is on — then only records
     * [pendingChange] and leaves [dto] untouched, matching the behaviour this state object replaced,
     * `renderInvoiceOrDeferIfEditing`.
     */
    fun applyChange(current: InvoiceDetailDto) {
        if (editMode) {
            pendingChange = true
        } else {
            dto = current
            listener?.invoke(current)
        }
    }

    fun enterEditMode() {
        editMode = true
    }

    /** Leaves entry mode and discards any deferred-change flag (consumed, not replayed — same as
     *  the earlier `exitEntryMode` this method replaced, which never re-read [pendingChange] either). */
    fun exitEditMode() {
        editMode = false
        pendingChange = false
    }
}

/** Reloads the committed invoice when [ex] is a concurrency conflict; any other failure leaves
 *  the editor untouched. Nullable receiver: [InvoiceStatusActions] is bound lazily. */
internal fun InvoiceDetailState?.reloadOnConflict(ex: Exception) {
    if (isConcurrencyConflict(ex)) this?.reloadAfterConflict()
}
