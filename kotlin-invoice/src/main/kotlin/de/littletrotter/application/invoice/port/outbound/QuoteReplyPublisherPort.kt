package de.littletrotter.application.invoice.port.outbound

import de.littletrotter.application.invoice.CreateInvoiceFromQuoteCommand

/**
 * Tells the offer service what became of an accepted offer. Implementations append to the
 * transactional outbox, so the call must run inside the transaction that made the outcome true
 * (or in its own short one for an outcome that created nothing).
 */
interface QuoteReplyPublisherPort {

    fun created(command: CreateInvoiceFromQuoteCommand, invoiceId: Long, invoiceNumber: String)

    fun rejected(command: CreateInvoiceFromQuoteCommand, reason: String)
}
