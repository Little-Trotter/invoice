package de.littletrotter.ui.view.quote

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.menubar.MenuBar
import com.vaadin.flow.component.menubar.MenuBarVariant
import com.vaadin.flow.router.QueryParameters
import de.littletrotter.application.quote.port.inbound.QuoteDetail
import de.littletrotter.application.quote.port.inbound.QuoteDispatchHandover
import de.littletrotter.application.quote.port.inbound.QuoteInvoiceOrder
import de.littletrotter.application.quote.port.inbound.QuoteInvoiceOrderRequest
import de.littletrotter.application.quote.port.inbound.QuoteInvoiceOrderStatus
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.notification.UiNotifications
import de.littletrotter.ui.view.mail.MailComposeView
import java.time.LocalDate

/**
 * The action bar of the offer detail: builds the buttons the status and the caller's permission
 * allow, one of them prominent, the rare and destructive ones in an overflow menu, and runs each
 * action against the offer component. [onChanged] reloads the view after an
 * action succeeded; editing and sending navigate away instead.
 */
internal class QuoteDetailActions(
    private val host: TranslationProvider,
    private val ports: QuoteLifecyclePorts,
    private val time: TimeSource,
    private val onChanged: () -> Unit,
) : Div() {

    private var quoteId = ""
    private var status = QuoteStatus.DRAFT
    private var orderReference: String? = null
    private var lastOrder: QuoteInvoiceOrder? = null

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
        quoteId = detail.id
        status = detail.status
        lastOrder = detail.invoiceOrder
        orderReference = detail.customerOrderReference
        removeAll()
        val layout = QuoteActionRules.layout(QuoteActionRules.available(detail, mayEdit, maySend, mayRequestInvoice))
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
    }

    private fun button(action: QuoteAction, primary: Boolean): Button {
        val text = caption(action)
        val onClick: () -> Unit = { run(action) }
        val button = when {
            action == QuoteAction.SEND -> AppButtons.secondary(text, ActionIcons.SEND_MAIL, onClick)
            primary -> AppButtons.primary(text, icon(action), onClick)
            else -> AppButtons.tertiary(text, icon(action), onClick)
        }
        return button.apply {
            setId("quote-action-${action.name}")
            addClassNames("ch-action-btn")
            if (action == QuoteAction.SEND) addClassNames("ch-btn--mail")
        }
    }

    private fun icon(action: QuoteAction): VaadinIcon =
        when (action) {
            QuoteAction.EDIT -> ActionIcons.EDIT
            QuoteAction.SUBMIT -> ActionIcons.VIEW
            QuoteAction.RETURN_TO_DRAFT -> VaadinIcon.ARROW_BACKWARD
            QuoteAction.CANCEL -> VaadinIcon.CLOSE_CIRCLE
            QuoteAction.ACCEPT -> ActionIcons.CONFIRM
            QuoteAction.REJECT -> ActionIcons.CANCEL
            QuoteAction.REQUEST_INVOICE -> ActionIcons.PDF
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
            QuoteAction.EDIT -> UI.getCurrent().navigate(QuoteRoutes.edit(quoteId))
            QuoteAction.SUBMIT -> submit()
            QuoteAction.RETURN_TO_DRAFT -> QuoteTextDialog(host, "quote.dialog.return") { returnToDraft(it) }.open()
            QuoteAction.CANCEL -> QuoteTextDialog(host, "quote.dialog.cancel") { cancel(it) }.open()
            QuoteAction.ACCEPT -> QuoteAcceptDialog(host, time) { on, order -> accept(on, order) }.open()
            QuoteAction.REQUEST_INVOICE -> requestInvoiceDialog().open()
            QuoteAction.REJECT -> QuoteRejectDialog(host) { reject(it) }.open()
            QuoteAction.SEND -> send()
        }
    }

    private fun submit() {
        val number = guarded { ports.submit.submit(quoteId) } ?: return
        UiNotifications.success(host.tr("quote.submitted", number))
        onChanged()
    }

    private fun send() {
        when (val handover = guarded { ports.send.send(quoteId) } ?: return) {
            is QuoteDispatchHandover.Composed -> UI.getCurrent().navigate(
                "mail/compose/${handover.draftId}",
                QueryParameters.simple(mapOf(MailComposeView.QUOTE_PARAMETER to quoteId)),
            )
            is QuoteDispatchHandover.DocumentPending -> {
                UiNotifications.info(host.tr("quote.send.documentPending"))
                onChanged()
            }
        }
    }

    private fun requestInvoiceDialog() =
        QuoteInvoiceOrderDialog(host, time, status == QuoteStatus.SENT, orderReference, lastOrder) { requestInvoice(it) }

    private fun requestInvoice(order: QuoteInvoiceOrderRequest): Boolean =
        done { ports.requestInvoice.request(quoteId, order) }

    private fun returnToDraft(note: String): Boolean = done { ports.returnToDraft.returnToDraft(quoteId, note) }

    private fun cancel(reason: String): Boolean = done { ports.cancel.cancel(quoteId, reason) }

    private fun accept(on: LocalDate, orderReference: String?): Boolean =
        done { ports.accept.accept(quoteId, on, orderReference) }

    private fun reject(reason: String): Boolean = done { ports.reject.reject(quoteId, reason) }

    private fun done(call: () -> Unit): Boolean {
        val ok = guarded(call) != null
        if (ok) onChanged()
        return ok
    }
}
