package de.littletrotter.application.invoice.port.inbound

import de.littletrotter.application.command.CommandEntryPoint
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.UpdateInvoiceHeaderCommand

/**
 * Input port of the "edit an invoice's header" use case: header fields, the Reverse-Charge
 * decision, recalculation and the Stammdaten (party snapshot) refresh. [CommandEntryPoint]
 * marks this as the surface `CommandBoundaryArchitectureTest` scans for entity
 * leaks.
 */
interface EditInvoiceHeaderUseCase : CommandEntryPoint {
    /** Header fields from the Erfassen (entry) pane. */
    fun updateInvoiceHeader(command: UpdateInvoiceHeaderCommand): InvoiceDetailDto

    /** The header's own leading Reverse-Charge decision; converts every position accordingly. */
    fun setReverseChargeDecision(invoiceId: Long, expectedVersion: Long?, decision: Boolean): InvoiceDetailDto

    /** Recalculates totals independently of the invoice's own validation. */
    fun recalculateInvoice(invoiceId: Long): InvoiceDetailDto

    /** Replaces the invoice's frozen seller/buyer snapshot from current master data; blocked on
     *  a finalized/cancelled invoice. */
    fun refreshPartySnapshots(invoiceId: Long): InvoiceDetailDto
}
