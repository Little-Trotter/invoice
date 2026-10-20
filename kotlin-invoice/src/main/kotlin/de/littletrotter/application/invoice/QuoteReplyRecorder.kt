package de.littletrotter.application.invoice

import de.littletrotter.application.invoice.port.outbound.QuoteReplyPublisherPort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional

/**
 * Records a reply to the offer service in a transaction of its own, for outcomes that created no
 * invoice in this call: a draft that already existed (the earlier reply may have been lost) and a
 * refusal. The reply of a freshly created draft is recorded inside the draft's own transaction.
 */
@ApplicationScoped
class QuoteReplyRecorder @Inject constructor(
    private val replies: QuoteReplyPublisherPort,
) {

    @Transactional
    fun created(command: CreateInvoiceFromQuoteCommand, invoiceId: Long, invoiceNumber: String) {
        replies.created(command, invoiceId, invoiceNumber)
    }

    @Transactional
    fun rejected(command: CreateInvoiceFromQuoteCommand, reason: String) {
        replies.rejected(command, reason)
    }
}
