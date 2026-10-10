package de.littletrotter.application.invoice

import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.port.inbound.ChangeInvoiceStatusUseCase
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Event
import jakarta.inject.Inject

/** Implements [ChangeInvoiceStatusUseCase] on top of [InvoiceLifecycleService],
 *  [InvoiceSendService] and [InvoiceStornoService]. */
@ApplicationScoped
class ChangeInvoiceStatus @Inject constructor(
    private val invoiceLifecycleService: InvoiceLifecycleService,
    private val invoiceSendService: InvoiceSendService,
    private val invoiceStornoService: InvoiceStornoService,
    private val invoiceReadRepository: InvoiceReadRepository,
    private val creditNoteIssued: Event<CreditNoteIssued>,
) : ChangeInvoiceStatusUseCase {

    override fun transitionInvoice(invoiceId: Long, target: InvoiceStatus, overrideReason: String?): InvoiceDetailDto =
        reload(invoiceLifecycleService.transition(invoiceId, target, overrideReason))

    // PO decision 2026-09-13 — requestSend never returns a managed Invoice (InvoiceSendService
    // only creates a GenerationJob, the invoice itself is untouched), so this reloads by id
    // directly rather than through the shared reload(Invoice) helper below.
    override fun requestSend(invoiceId: Long): InvoiceDetailDto {
        invoiceSendService.requestSend(invoiceId)
        return checkNotNull(invoiceReadRepository.findInvoiceDetailDto(invoiceId)) { "Invoice $invoiceId not found after requesting send" }
    }

    override fun createCreditNote(invoiceId: Long, reason: String, reasonEn: String?): InvoiceDetailDto {
        val creditNote = invoiceStornoService.createCreditNote(invoiceId, reason, reasonEn)
        // Synchronous fire: AFTER_SUCCESS observers only apply to a synchronously fired event. With
        // an outer caller transaction the observer waits for its commit; without one it runs now.
        creditNoteIssued.fire(CreditNoteIssued(checkNotNull(creditNote.id) { "Credit note has no id after storno" }))
        return reload(creditNote)
    }

    private fun reload(invoice: Invoice): InvoiceDetailDto =
        checkNotNull(invoiceReadRepository.findInvoiceDetailDto(invoice.id!!)) { "Invoice ${invoice.id} not found after save" }
}
