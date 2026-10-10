package de.littletrotter.infrastructure.messaging.acl

import de.littletrotter.application.invoice.CreateInvoiceFromQuoteCommand
import de.littletrotter.application.invoice.port.outbound.QuoteReplyPublisherPort
import de.littletrotter.infrastructure.messaging.OutboxAppended
import de.littletrotter.infrastructure.messaging.OutboxRepository
import de.littletrotter.shared.quote.InvoiceCreatedFromQuoteEvent
import de.littletrotter.shared.quote.InvoiceCreationFromQuoteRejectedEvent
import de.littletrotter.shared.quote.QuoteSubjects
import de.littletrotter.shared.time.TimeSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Event
import jakarta.inject.Inject
import java.util.UUID

/**
 * Encodes the replies to the offer service in the published offer language and appends them to
 * the transactional outbox; the relay publishes them once the surrounding transaction committed.
 * The reply reuses the id of the order it answers, so a reply sent twice is recognisably the
 * same fact.
 */
@ApplicationScoped
class QuoteReplyOutboxPublisher @Inject constructor(
    private val outbox: OutboxRepository,
    private val appended: Event<OutboxAppended>,
    private val time: TimeSource,
) : QuoteReplyPublisherPort {

    override fun created(command: CreateInvoiceFromQuoteCommand, invoiceId: Long, invoiceNumber: String) {
        val event = InvoiceCreatedFromQuoteEvent(
            eventId = command.orderId,
            quoteId = command.quoteId,
            quoteNumber = command.quoteNumber,
            orderId = command.orderId,
            invoiceId = invoiceId,
            invoiceNumber = invoiceNumber,
            occurredAt = time.instant(),
        )
        append(command.quoteId, QuoteSubjects.INVOICE_CREATED, InvoiceCreatedFromQuoteEvent.encode(event))
    }

    override fun rejected(command: CreateInvoiceFromQuoteCommand, reason: String) {
        val event = InvoiceCreationFromQuoteRejectedEvent(
            eventId = command.orderId,
            quoteId = command.quoteId,
            quoteNumber = command.quoteNumber,
            orderId = command.orderId,
            reason = reason,
            occurredAt = time.instant(),
        )
        append(command.quoteId, QuoteSubjects.INVOICE_REJECTED, InvoiceCreationFromQuoteRejectedEvent.encode(event))
    }

    private fun append(quoteId: UUID, subject: String, payload: ByteArray) {
        outbox.append(AGGREGATE_TYPE, quoteId.toString(), subject, String(payload, Charsets.UTF_8))
        appended.fire(OutboxAppended(subject))
    }

    private companion object {
        const val AGGREGATE_TYPE = "QUOTE"
    }
}
