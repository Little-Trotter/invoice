package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.AttachEvent
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.DetachEvent
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.menubar.MenuBar
import com.vaadin.flow.component.menubar.MenuBarVariant
import com.vaadin.flow.router.QueryParameters
import de.littletrotter.application.quote.QuoteNotSendableException
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.inbound.QuoteDetail
import de.littletrotter.application.quote.port.inbound.QuoteDocumentMark
import de.littletrotter.application.quote.port.inbound.QuoteDocumentStatus
import de.littletrotter.application.quote.port.inbound.QuoteInvoiceOrder
import de.littletrotter.application.quote.port.inbound.QuoteInvoiceOrderRequest
import de.littletrotter.application.quote.port.inbound.QuoteInvoiceOrderStatus
import de.littletrotter.application.quote.port.inbound.QuoteSendGateState
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.ui.UiEventBus
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.notification.UiNotifications
import de.littletrotter.ui.view.invoice.DETAIL_POLL_INTERVAL_MS
import de.littletrotter.ui.view.invoice.PollingViewLifecycle
import de.littletrotter.ui.view.invoice.pollTick
import de.littletrotter.ui.view.invoice.runOffLock
import de.littletrotter.ui.view.mail.MailComposeView
import java.time.Duration
import java.time.LocalDate

/**
 * The action bar of the offer detail: builds the buttons the status and the caller's permission
 * allow, one of them prominent, the rare and destructive ones in an overflow menu, and runs each
 * action against the offer component. [onChanged] reloads the view after an
 * action succeeded; editing and sending navigate away instead.
 *
 * Sending is one action: "E-Mail senden" stays visible but disabled, with the first blocker as
 * tooltip, while the offer component's send gate is closed for a reason the user must fix. When only
 * the dispatch copy is missing or outdated, the click requests it, the button counts the seconds and
 * the normal send flow opens by itself once the copy is ready. Without a gate port (no mail component deployed) no send action is offered. The
 * hero is rebuilt on every poll tick of the documents tab, so the elapsed-seconds line advances
 * without a timer of its own; the seconds are exposed as [copySecondsInFlight] for the hero's fact rail.
 */
internal class QuoteDetailActions(
    private val host: TranslationProvider,
    private val ports: QuoteLifecyclePorts,
    private val documents: QuoteDocumentPorts,
    private val time: TimeSource,
    private val onChanged: () -> Unit,
) : Div() {

    private var quoteId = ""
    private var quoteNumber = ""
    private var status = QuoteStatus.DRAFT
    private var orderReference: String? = null
    private var lastOrder: QuoteInvoiceOrder? = null
    private var mayEdit = false
    private var mayRequestInvoice = false
    private var gate = QuoteSendGateState.NOT_IN_REVIEW

    /** The user clicked send and the dispatch copy it needs is still to be made. */
    private var sendWhenCopyReady = false

    /**
     * Polls the gate while a send click waits for its dispatch copy. The documents tab polls only
     * while it is on screen, so the action bar needs its own tick or the wait would never end.
     */
    private val polling = PollingViewLifecycle()
    private var polled = false
    private var redraw: () -> Unit = {}
    private var polledGate: QuoteSendGateState? = null

    /** The gate reported the copy as being made since that click; a later missing copy means it failed. */
    private var copySeenInFlight = false

    /**
     * Seconds since the newest dispatch copy was requested while one is being made, else `null`. The
     * hero shows the running text as the lead fact of its rail, so the action bar stays free of it.
     */
    var copySecondsInFlight: Long? = null
        private set

    init {
        addClassNames("quote-actions", "ch-actions")
    }

    fun render(
        detail: QuoteDetail,
        mayEdit: Boolean,
        maySend: Boolean,
        mayRequestInvoice: Boolean,
        export: Component? = null,
    ) {
        this.mayEdit = mayEdit
        this.mayRequestInvoice = mayRequestInvoice
        redraw = { render(detail, mayEdit, maySend, mayRequestInvoice, export) }
        quoteId = detail.id
        quoteNumber = detail.requireNumber()
        status = detail.status
        lastOrder = detail.invoiceOrder
        orderReference = detail.customerOrderReference
        removeAll()
        val sendAvailable = maySend && ports.sendGate != null && detail.status == QuoteStatus.REVIEW
        gate = if (sendAvailable) currentGate() else QuoteSendGateState.NOT_IN_REVIEW
        val layout = QuoteActionRules.layout(
            QuoteActionRules.available(detail, mayEdit, sendAvailable, mayRequestInvoice),
        )
        copySecondsInFlight = if (sendAvailable && gate == QuoteSendGateState.COPY_IN_FLIGHT) elapsedSecondsOfPendingCopy() else null
        // The export belongs to the send action: it sits right before it, or before the primary action
        // when the status offers no send.
        var exportPlaced = export == null
        fun placeExport(before: Boolean) {
            if (before && !exportPlaced) {
                add(requireNotNull(export))
                exportPlaced = true
            }
        }
        layout.secondary.forEach {
            placeExport(it == QuoteAction.SEND)
            add(button(it, primary = false))
        }
        layout.primary?.let {
            placeExport(true)
            add(button(it, primary = true))
        }
        placeExport(true)
        if (layout.overflow.isNotEmpty()) add(overflowMenu(layout.overflow))
        continuePendingSend()
        syncPolling()
    }

    /** The hero re-parents the bar on every redraw, which detaches and attaches it again. */
    override fun onAttach(attachEvent: AttachEvent) {
        super.onAttach(attachEvent)
        syncPolling()
    }

    override fun onDetach(detachEvent: DetachEvent) {
        super.onDetach(detachEvent)
        if (polled) {
            polled = false
            polling.detach(detachEvent.ui)
        }
    }

    private fun syncPolling() {
        val current = ui.orElse(null)
        if (sendWhenCopyReady && !polled && current != null) {
            polled = true
            polling.attach(
                current,
                DETAIL_POLL_INTERVAL_MS,
                register = { UiEventBus.Registration { } },
                tick = pollTick(load = ::loadGateQuietly, apply = ::applyPolledGate),
            )
        } else if (!sendWhenCopyReady && polled) {
            polled = false
            polling.detach(current)
        }
    }

    /** Runs off the session lock; an unreachable offer component is retried on the next tick. */
    private fun loadGateQuietly(): QuoteSendGateState? =
        try {
            ports.sendGate?.gate(quoteId)
        } catch (_: QuoteServiceException) {
            null
        }

    /** Redraws the bar with the gate the tick read, which also advances the seconds counter. */
    internal fun applyPolledGate(read: QuoteSendGateState?) {
        if (read == null || !isAttached || !sendWhenCopyReady) return
        polledGate = read
        redraw()
    }

    /**
     * Follows up a send click that had to wait for its dispatch copy: opens the send flow once the
     * gate is open, reports a copy that failed (the gate falls back from "being made" to missing or
     * outdated) and forgets the click when another blocker took over.
     */
    private fun continuePendingSend() {
        if (!sendWhenCopyReady) return
        when (gate) {
            QuoteSendGateState.COPY_IN_FLIGHT -> copySeenInFlight = true
            QuoteSendGateState.READY -> {
                sendWhenCopyReady = false
                children.filter { it.id.orElse("") == SEND_BUTTON_ID }.findFirst().ifPresent { send(it as Button) }
            }
            QuoteSendGateState.COPY_MISSING, QuoteSendGateState.COPY_STALE -> if (copySeenInFlight) {
                sendWhenCopyReady = false
                UiNotifications.error(host.tr(requireNotNull(sendBlockerTextKey(gate))))
            }
            QuoteSendGateState.NOT_IN_REVIEW, QuoteSendGateState.NO_RECIPIENT_EMAIL -> sendWhenCopyReady = false
        }
    }

    /** A gate that cannot be read is reported once and keeps the send closed. */
    private fun currentGate(): QuoteSendGateState {
        polledGate?.let {
            polledGate = null
            return it
        }
        return guarded { requireNotNull(ports.sendGate).gate(quoteId) } ?: QuoteSendGateState.NOT_IN_REVIEW
    }

    /** The seconds run from the request of the newest dispatch copy still being made. */
    private fun elapsedSecondsOfPendingCopy(): Long {
        // Runs on every poll tick: a momentarily unreachable offer component must not raise a notification
        // per tick, so the counter simply shows no elapsed time until the next tick succeeds.
        val requested = try {
            documents.documents.documents(quoteId)
                .filter { it.mark == QuoteDocumentMark.DISPATCH && it.status == QuoteDocumentStatus.REQUESTED }
                .maxOfOrNull { it.requestedAt }
        } catch (_: QuoteServiceException) {
            null
        }
        return requested?.let { Duration.between(it, time.nowUtc().toInstant()).seconds.coerceAtLeast(0) } ?: 0
    }

    private fun button(action: QuoteAction, primary: Boolean): Button {
        val text = caption(action)
        lateinit var sendButton: Button
        val onClick: () -> Unit = { if (action == QuoteAction.SEND) sendOrMakeCopy(sendButton) else run(action) }
        val button = when {
            action == QuoteAction.SEND -> {
                val made = if (primary) AppButtons.primary(text, ActionIcons.SEND_MAIL, onClick) else AppButtons.secondary(text, ActionIcons.SEND_MAIL, onClick)
                made.also { sendButton = it }
            }
            primary -> AppButtons.primary(text, icon(action), onClick)
            else -> AppButtons.tertiary(text, icon(action), onClick)
        }
        return button.apply {
            setId(idOf(action))
            addClassNames("ch-action-btn")
            if (action == QuoteAction.SEND) {
                addClassNames("ch-btn--mail")
                isDisableOnClick = true
                if (gate in COPY_REQUESTABLE) return@apply
                sendBlockerTextKey(gate)?.let {
                    isEnabled = false
                    element.setAttribute("title", host.tr(it))
                }
                copySecondsInFlight?.let { seconds ->
                    // The button the user clicked stays where it was, disabled, and counts the seconds.
                    this.text = host.tr("quote.send.copyInProgress.button", seconds)
                    addClassName("ch-btn--busy")
                }
            }
        }
    }

    private fun icon(action: QuoteAction): VaadinIcon =
        when (action) {
            QuoteAction.EDIT -> ActionIcons.EDIT
            QuoteAction.SUBMIT -> ActionIcons.VIEW
            QuoteAction.RETURN_TO_DRAFT -> ActionIcons.REVERT
            QuoteAction.CANCEL -> VaadinIcon.CLOSE_CIRCLE
            QuoteAction.ACCEPT -> ActionIcons.CONFIRM
            QuoteAction.REJECT -> ActionIcons.CANCEL
            QuoteAction.REQUEST_INVOICE -> ActionIcons.CREATE_INVOICE
            QuoteAction.SEND -> ActionIcons.SEND_MAIL
        }

    /** A refused order is placed again with the same action, which then says so. */
    private fun caption(action: QuoteAction): String =
        if (action == QuoteAction.REQUEST_INVOICE && lastOrder?.status == QuoteInvoiceOrderStatus.REJECTED) {
            host.tr("quote.action.REQUEST_INVOICE_AGAIN")
        } else {
            host.tr("quote.action.${action.name}")
        }

    /** One icon button whose menu holds the actions that are rare or cannot be undone. */
    private fun overflowMenu(actions: List<QuoteAction>): MenuBar =
        MenuBar().apply {
            addThemeVariants(MenuBarVariant.LUMO_TERTIARY, MenuBarVariant.LUMO_ICON)
            setId("quote-actions-overflow")
            val root = addItem(ActionIcons.MORE.create())
            root.element.setAttribute(ARIA_LABEL, host.tr("quote.action.menu"))
            actions.forEach { action -> root.subMenu.addItem(host.tr("quote.action.${action.name}")) { run(action) } }
        }

    private fun run(action: QuoteAction) {
        when (action) {
            QuoteAction.EDIT -> UI.getCurrent().navigate(QuoteRoutes.edit(quoteNumber))
            QuoteAction.SUBMIT -> submit()
            QuoteAction.RETURN_TO_DRAFT -> QuoteTextDialog(host, "quote.dialog.return") { returnToDraft(it) }.open()
            QuoteAction.CANCEL -> QuoteTextDialog(host, "quote.dialog.cancel") { cancel(it) }.open()
            QuoteAction.ACCEPT -> QuoteAcceptDialog(host, time, mayEdit && mayRequestInvoice) { on, order, orderNow ->
                accept(on, order, orderNow)
            }.open()
            QuoteAction.REQUEST_INVOICE -> requestInvoiceDialog().open()
            QuoteAction.REJECT -> QuoteRejectDialog(host) { reject(it) }.open()
            QuoteAction.SEND -> Unit
        }
    }

    private fun submit() {
        val number = guarded { ports.submit.submit(quoteId) } ?: return
        UiNotifications.success(host.tr("quote.submitted", number))
        onChanged()
    }

    /** A missing or outdated dispatch copy is requested first; the send flow follows when it is ready. */
    private fun sendOrMakeCopy(button: Button) {
        if (gate !in COPY_REQUESTABLE) return send(button)
        sendWhenCopyReady = true
        copySeenInFlight = false
        if (guarded { documents.request.request(quoteId, QuoteDocumentMark.DISPATCH) } != null) {
            syncPolling()
            onChanged()
        } else {
            sendWhenCopyReady = false
            button.isEnabled = true
        }
    }

    /**
     * The click only switches the button to its loading state, so the browser shows it in the click's
     * own response; the handover (an HTTP call into the offer component) runs off the session lock and
     * its outcome is applied through the UI's push channel. A gate that closed after the page was
     * drawn is shown as its own text and the page redrawn.
     */
    private fun send(button: Button) {
        val label = button.text
        button.isEnabled = false
        button.text = host.tr("quote.send.composing")
        runOffLock(this, { ports.send.send(quoteId) }) { outcome ->
            // The user left the view while the handover ran: there is nothing left to update.
            if (!isAttached) return@runOffLock
            outcome.onFailure {
                button.text = label
                button.isEnabled = true
            }
            val handover = guarded {
                try {
                    outcome.getOrThrow()
                } catch (e: QuoteNotSendableException) {
                    UiNotifications.error(host.tr(requireNotNull(sendBlockerTextKey(e.gate)) { "an open gate cannot refuse" }))
                    onChanged()
                    null
                }
            } ?: return@runOffLock
            UI.getCurrent().navigate(
                "mail/compose/${handover.draftId}",
                QueryParameters.simple(mapOf(MailComposeView.QUOTE_PARAMETER to quoteNumber)),
            )
        }
    }

    private fun requestInvoiceDialog(needsAcceptance: Boolean = status == QuoteStatus.SENT) =
        QuoteInvoiceOrderDialog(host, time, needsAcceptance, orderReference, lastOrder) { requestInvoice(it) }

    private fun requestInvoice(order: QuoteInvoiceOrderRequest): Boolean =
        done { ports.requestInvoice.request(quoteId, order) }

    private fun returnToDraft(note: String): Boolean = done { ports.returnToDraft.returnToDraft(quoteId, note) }

    private fun cancel(reason: String): Boolean = done { ports.cancel.cancel(quoteId, reason) }

    /**
     * With the order option checked, the invoice order dialog of the accepted offer follows the
     * acceptance; the acceptance is already recorded then, so it does not ask for the day again.
     * The order reference is passed on because the reloaded offer may not be drawn yet.
     */
    private fun accept(on: LocalDate, orderReference: String?, orderNow: Boolean): Boolean {
        val ok = done { ports.accept.accept(quoteId, on, orderReference) }
        if (ok && orderNow) {
            this.orderReference = orderReference ?: this.orderReference
            requestInvoiceDialog(needsAcceptance = false).open()
        }
        return ok
    }

    private fun reject(reason: String): Boolean = done { ports.reject.reject(quoteId, reason) }

    private fun done(call: () -> Unit): Boolean {
        val ok = guarded(call) != null
        if (ok) onChanged()
        return ok
    }
}

private const val SEND_BUTTON_ID = "quote-action-SEND"

private fun idOf(action: QuoteAction) = "quote-action-${action.name}"

/** Gate states in which the send click can fix the blocker itself by requesting a dispatch copy. */
private val COPY_REQUESTABLE = setOf(QuoteSendGateState.COPY_MISSING, QuoteSendGateState.COPY_STALE)

/**
 * Message key of the first thing that keeps the offer from being sent, or `null` when the gate is
 * open. The gate enum is ordered by priority, so mapping the one value the port returned is the
 * "first blocker wins" rule.
 */
internal fun sendBlockerTextKey(gate: QuoteSendGateState): String? =
    when (gate) {
        QuoteSendGateState.READY -> null
        QuoteSendGateState.NOT_IN_REVIEW -> "quote.send.gate.notInReview"
        QuoteSendGateState.COPY_IN_FLIGHT -> "quote.send.gate.copyInFlight"
        QuoteSendGateState.COPY_STALE -> "quote.send.gate.copyStale"
        QuoteSendGateState.COPY_MISSING -> "quote.send.gate.copyMissing"
        QuoteSendGateState.NO_RECIPIENT_EMAIL -> "quote.send.gate.noRecipientEmail"
    }
