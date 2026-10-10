package de.littletrotter.application.invoice.port.inbound

import de.littletrotter.application.command.CommandEntryPoint
import de.littletrotter.application.dto.InvoiceDetailDto

/**
 * Input port of the "create an invoice for a seller/recipient pair" use case
 * ([de.littletrotter.ui.view.invoice.InvoiceCreateDialog]'s only write). [CommandEntryPoint]
 * marks this as the surface `CommandBoundaryArchitectureTest` scans for entity
 * leaks.
 */
interface CreateInvoiceUseCase : CommandEntryPoint {
    fun createInvoiceWithRecipient(defaultSellerId: Long, recipientId: Long, reverseChargeDecision: Boolean? = null): InvoiceDetailDto
}
