package de.littletrotter.infrastructure.messaging

import de.littletrotter.application.invoice.port.inbound.CreateInvoiceFromQuoteUseCase
import de.littletrotter.application.dto.InvoiceFromQuoteResult
import de.littletrotter.domain.event.DomainEvent
import de.littletrotter.domain.event.InvoiceCreationFromQuoteRejected
import de.littletrotter.infrastructure.messaging.acl.QuoteInvoiceOrderTranslator
import de.littletrotter.shared.quote.QuoteInvoiceOrderRequested
import de.littletrotter.shared.quote.QuoteContractViolationException
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Event
import jakarta.inject.Inject
import org.jboss.logging.Logger

/**
 * The business half of the acceptance subscription: decodes one payload and hands it to the
 * use case. Split from [QuoteInvoiceOrderNatsAdapter] so every outcome is testable without a broker.
 *
 * A payload that violates the contract is not swallowed: [QuoteContractViolationException]
 * propagates so the adapter terminates the message instead of acknowledging something nobody
 * could read. The use case answers the offer service itself (created or rejected), so a returned
 * result needs no further action here.
 */
@ApplicationScoped
class QuoteInvoiceOrderConsumer @Inject constructor(
    private val createInvoice: CreateInvoiceFromQuoteUseCase,
    private val events: Event<DomainEvent>,
) {

    /** Drafts the invoice for one acceptance; throws on an unreadable payload or a transient failure. */
    fun consume(raw: ByteArray): InvoiceFromQuoteResult =
        createInvoice.create(QuoteInvoiceOrderTranslator.toCommand(QuoteInvoiceOrderRequested.decode(raw)))

    /**
     * Tells the clerk that an acceptance was given up after repeated failures. The offer number is
     * read from the payload when that still works; the message is archived either way.
     */
    fun reportParked(raw: ByteArray) {
        val quoteNumber = try {
            QuoteInvoiceOrderRequested.decode(raw).quoteNumber
        } catch (e: QuoteContractViolationException) {
            log.debug("Parked payload is unreadable, naming the offer as unknown", e)
            UNKNOWN_QUOTE
        }
        events.fireAsync(InvoiceCreationFromQuoteRejected(quoteNumber, PARKED_REASON))
    }

    private val log = Logger.getLogger(QuoteInvoiceOrderConsumer::class.java)

    private companion object {
        const val UNKNOWN_QUOTE = "unbekannt"
        const val PARKED_REASON = "Verarbeitung mehrfach fehlgeschlagen"
    }
}
