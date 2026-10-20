package de.littletrotter.application.invoice.port.inbound

import de.littletrotter.application.command.CommandEntryPoint
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.domain.invoice.InvoiceStatus

/**
 * Input port of the "change an invoice's status" use case: lifecycle transitions, the
 * send request and Cancellation (credit note). [CommandEntryPoint] marks this as the
 * surface `CommandBoundaryArchitectureTest` scans for entity leaks.
 */
interface ChangeInvoiceStatusUseCase : CommandEntryPoint {
    /** [overrideReason] is only consulted when the invoice's validation is `UNAVAILABLE` and
     *  [target] is `ISSUED`. */
    fun transitionInvoice(invoiceId: Long, target: InvoiceStatus, overrideReason: String? = null): InvoiceDetailDto

    /** The ONLY UI-reachable path to `ISSUED`: requests a `SEND`-triggered generation job and
     *  returns immediately, never changing the invoice's own status. */
    fun requestSend(invoiceId: Long): InvoiceDetailDto

    /** Creates the credit note that cancels out [invoiceId]'s effect. [reason] is the free-text
     *  Cancellation-Grund, mandatory (non-blank). Callers must gate visibility with
     *  [de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
     *  .canCreateCreditNote] first — this still throws on an ineligible invoice. [reasonEn] is an
     *  optional English reason, only ever consulted when the invoice's own document language is
     *  `"en"`. */
    fun createCreditNote(invoiceId: Long, reason: String, reasonEn: String? = null): InvoiceDetailDto
}
