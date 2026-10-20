package de.littletrotter.application.invoice

import de.littletrotter.domain.exception.InvoiceWithoutSenderProfileException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * The seam through which an invoice draws its number. Two call sites need it and must not each
 * carry their own copy: [InvoiceLifecycleService.transition] draws it at the `→ ISSUED` edge, and
 * [InvoiceCancellationService.createCreditNote] draws it when the cancellation document is created — that
 * document is terminal from birth (ST-3) and so never reaches the `→ ISSUED` edge at all.
 *
 * A number is always drawn inside the caller's own transaction: if that transaction rolls back,
 * the number was never consumed and the sequence stays gapless.
 */
fun interface InvoiceNumberAssigner {

    /** Assigns the next number from the sender's active range, unless [invoice] already carries
     *  one. Idempotent by that check — an already-numbered document is never renumbered. */
    fun assignIfNeeded(invoice: Invoice)
}

/**
 * The one implementation, backed by [InvoiceNumberService]'s consuming counter.
 *
 * Callers must have every field the allocation depends on in its final state before calling:
 * the counter query is native and forces Hibernate to flush the entity's current dirty state, so
 * an invoice whose date or payment-term snapshot is still half-updated would be flushed in that
 * half-updated form.
 */
@ApplicationScoped
class InvoiceNumberAssignment @Inject constructor(
    private val invoiceNumberService: InvoiceNumberService,
) : InvoiceNumberAssigner {

    override fun assignIfNeeded(invoice: Invoice) {
        if (invoice.invoiceNumber.isAssigned()) return
        // No SenderProfileNotFoundException(-1L) here: no sender id was looked up and missed, the
        // relation is simply absent. The invoice's own id is the only meaningful identifier.
        val sender = invoice.senderProfile ?: throw InvoiceWithoutSenderProfileException(invoice.id)
        val allocated = invoiceNumberService.generateNextNumber(sender, invoice.invoiceDate, invoice.id)
        invoice.assignNumber(InvoiceNumber(allocated.value), allocated.prefix, allocated.sequence)
    }
}
