package de.littletrotter.application.invoice

import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.port.inbound.EditInvoiceHeaderUseCase
import de.littletrotter.domain.invoice.Invoice
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Implements [EditInvoiceHeaderUseCase] on top of [InvoiceHeaderUpdateService],
 *  [InvoiceReverseChargeService] and [InvoiceLifecycleService]. */
@ApplicationScoped
class EditInvoiceHeader @Inject constructor(
    private val headerUpdateService: InvoiceHeaderUpdateService,
    private val reverseChargeService: InvoiceReverseChargeService,
    private val invoiceLifecycleService: InvoiceLifecycleService,
    private val invoiceReadRepository: InvoiceReadRepository,
) : EditInvoiceHeaderUseCase {

    override fun updateInvoiceHeader(command: UpdateInvoiceHeaderCommand): InvoiceDetailDto =
        reload(headerUpdateService.updateHeader(command))

    override fun setReverseChargeDecision(invoiceId: Long, expectedVersion: Long?, decision: Boolean): InvoiceDetailDto =
        reload(reverseChargeService.setReverseChargeDecision(invoiceId, expectedVersion, decision))

    override fun recalculateInvoice(invoiceId: Long): InvoiceDetailDto =
        reload(invoiceLifecycleService.recalculate(invoiceId))

    override fun refreshPartySnapshots(invoiceId: Long): InvoiceDetailDto =
        reload(invoiceLifecycleService.refreshPartySnapshots(invoiceId))

    private fun reload(invoice: Invoice): InvoiceDetailDto =
        checkNotNull(invoiceReadRepository.findInvoiceDetailDto(invoice.id!!)) { "Invoice ${invoice.id} not found after save" }
}
