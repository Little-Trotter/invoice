package de.littletrotter.ui.view.quote

import de.littletrotter.application.quote.port.inbound.QuoteDetail
import de.littletrotter.application.quote.port.inbound.QuoteDocumentMark
import de.littletrotter.application.quote.port.inbound.QuoteInvoiceOrderStatus
import de.littletrotter.application.quote.port.inbound.QuoteStatus

/** The actions the offer detail can offer; declaration order is the order of the buttons. */
internal enum class QuoteAction { EDIT, SUBMIT, RETURN_TO_DRAFT, CANCEL, ACCEPT, REQUEST_INVOICE, REJECT, SEND }

/**
 * How the offered actions are presented: at most one [primary] button, [secondary] ones as quiet
 * buttons and the rare or destructive ones tucked into [overflow].
 */
internal data class QuoteActionLayout(
    val primary: QuoteAction?,
    val secondary: List<QuoteAction>,
    val overflow: List<QuoteAction>,
)

/**
 * Which life-cycle actions make sense for an offer. Mirrors the offer component's own rules only
 * to decide what to *offer*; the component stays the authority and refuses anything else.
 */
internal object QuoteActionRules {

    fun available(
        detail: QuoteDetail,
        mayEdit: Boolean,
        maySend: Boolean = false,
        mayRequestInvoice: Boolean = false,
    ): List<QuoteAction> {
        val send = if (maySend && detail.status == QuoteStatus.REVIEW) listOf(QuoteAction.SEND) else emptyList()
        val order = if (mayEdit && mayRequestInvoice && mayOrder(detail)) listOf(QuoteAction.REQUEST_INVOICE) else emptyList()
        val actions = send + editing(detail, mayEdit)
        // The order sits right after "accept" so the buttons read in the order of the offer's life.
        val at = actions.indexOf(QuoteAction.ACCEPT).let { if (it < 0) actions.size else it + 1 }
        return actions.take(at) + order + actions.drop(at)
    }

    /** A sent or accepted offer without an order that is waiting or done; a refused order may be placed again. */
    private fun mayOrder(detail: QuoteDetail): Boolean =
        (detail.status == QuoteStatus.SENT || detail.status == QuoteStatus.ACCEPTED) &&
            detail.invoiceOrder?.status.let { it != QuoteInvoiceOrderStatus.REQUESTED && it != QuoteInvoiceOrderStatus.CREATED }

    /**
     * Picks the one action the clerk most likely wants next: working on a draft, sending a
     * reviewed offer, accepting a sent one, ordering the invoice of an accepted one. Rejecting and annulling are never primary.
     */
    fun layout(actions: List<QuoteAction>): QuoteActionLayout {
        val primary = PRIMARY_ORDER.firstOrNull { it in actions }
        val overflow = actions.filter { it in OVERFLOW_ACTIONS }
        return QuoteActionLayout(primary, actions.filter { it != primary && it !in OVERFLOW_ACTIONS }, overflow)
    }

    private val PRIMARY_ORDER = listOf(QuoteAction.EDIT, QuoteAction.SEND, QuoteAction.ACCEPT, QuoteAction.REQUEST_INVOICE)
    private val OVERFLOW_ACTIONS = setOf(QuoteAction.CANCEL)

    /**
     * The stand a requested e-quote is generated in: it follows the offer status (draft as draft,
     * reviewed as review). The dispatch copy only comes out of sending, and closed offers get none.
     */
    fun requestMark(status: QuoteStatus): QuoteDocumentMark? =
        when (status) {
            QuoteStatus.DRAFT -> QuoteDocumentMark.DRAFT
            QuoteStatus.REVIEW -> QuoteDocumentMark.REVIEW
            QuoteStatus.SENT, QuoteStatus.ACCEPTED, QuoteStatus.INVOICED, QuoteStatus.REJECTED, QuoteStatus.CANCELLED -> null
        }

    private fun editing(detail: QuoteDetail, mayEdit: Boolean): List<QuoteAction> {
        if (!mayEdit) return emptyList()
        return when (detail.status) {
            QuoteStatus.DRAFT -> listOf(QuoteAction.EDIT, QuoteAction.SUBMIT, QuoteAction.CANCEL)
            QuoteStatus.REVIEW -> listOf(QuoteAction.EDIT, QuoteAction.RETURN_TO_DRAFT, QuoteAction.CANCEL)
            QuoteStatus.SENT -> listOf(QuoteAction.ACCEPT, QuoteAction.REJECT)
            QuoteStatus.ACCEPTED, QuoteStatus.INVOICED, QuoteStatus.REJECTED, QuoteStatus.CANCELLED -> emptyList()
        }
    }
}
