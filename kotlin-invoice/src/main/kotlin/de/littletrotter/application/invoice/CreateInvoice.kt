package de.littletrotter.application.invoice

import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.application.invoice.port.outbound.InvoiceReadRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Implements [CreateInvoiceUseCase] on top of [InvoiceLifecycleService.create]. */
@ApplicationScoped
class CreateInvoice @Inject constructor(
    private val invoiceLifecycleService: InvoiceLifecycleService,
    private val invoiceReadRepository: InvoiceReadRepository,
) : CreateInvoiceUseCase {

    override fun createInvoiceWithRecipient(
        defaultSellerId: Long,
        recipientId: Long,
        reverseChargeDecision: Boolean?,
    ): InvoiceDetailDto {
        val created = invoiceLifecycleService.create(defaultSellerId, recipientId, reverseChargeDecision)
        return checkNotNull(invoiceReadRepository.findInvoiceDetailDto(created.id!!)) { "Invoice ${created.id} not found after save" }
    }
}
