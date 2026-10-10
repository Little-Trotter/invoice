package de.littletrotter.application.invoice.port.inbound

import de.littletrotter.application.command.CommandEntryPoint
import de.littletrotter.application.invoice.CreateInvoiceFromQuoteCommand
import de.littletrotter.application.dto.InvoiceFromQuoteResult

/**
 * Turns an accepted offer into an invoice draft. The offer's content is read from the offer
 * service at call time, so the command carries only the reference and what the acceptance itself
 * added (date, the customer's own order number, who accepted).
 */
interface CreateInvoiceFromQuoteUseCase : CommandEntryPoint {

    /**
     * Idempotent per [CreateInvoiceFromQuoteCommand.quoteId]: a repeated call answers with the
     * existing draft and creates nothing. A business reason that makes the draft impossible is
     * answered as [InvoiceFromQuoteResult.Rejected]; transient failures (offer service not
     * reachable) propagate so the caller can retry.
     */
    fun create(command: CreateInvoiceFromQuoteCommand): InvoiceFromQuoteResult
}
