package de.littletrotter.application.invoice

import de.littletrotter.application.dto.InvoiceFromQuoteResult
import de.littletrotter.application.error.ApplicationValidationException
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceFromQuoteUseCase
import de.littletrotter.application.quote.QuoteNotFoundException
import de.littletrotter.application.shared.ActorOverride
import de.littletrotter.domain.event.DomainEvent
import de.littletrotter.domain.event.InvoiceCreatedFromQuote
import de.littletrotter.domain.event.InvoiceCreationFromQuoteRejected
import de.littletrotter.domain.exception.InvoiceDomainException
import de.littletrotter.domain.reference.NoActiveNumberRangeException
import de.littletrotter.domain.repository.InvoiceRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Event
import jakarta.inject.Inject

/**
 * Drafts an invoice from an offer for which an invoice order was placed.
 *
 * An offer is immutable from review on, so its content is read here, at consumption time
 * ([QuoteOfferReader]), instead of travelling inside the order. The reads happen before
 * the transaction of [QuoteInvoiceDraftWriter]; a failing offer service therefore surfaces as an
 * exception the consumer retries, never as a half-written invoice.
 *
 * The whole call runs as the person who placed the order, so the change journal, the audit
 * columns and the calls to the offer service name that person instead of "system".
 */
@ApplicationScoped
class CreateInvoiceFromQuote @Inject constructor(
    private val invoices: InvoiceRepository,
    private val reader: QuoteOfferReader,
    private val writer: QuoteInvoiceDraftWriter,
    private val replies: QuoteReplyRecorder,
    private val actorOverride: ActorOverride,
    private val events: Event<DomainEvent>,
) : CreateInvoiceFromQuoteUseCase {

    override fun create(command: CreateInvoiceFromQuoteCommand): InvoiceFromQuoteResult =
        actorOverride.runAs(command.actor) {
            val existing = invoices.findBySourceQuoteId(command.quoteId)
            if (existing != null) answerExisting(command, existing.id!!, existing.invoiceNumber.value) else draft(command)
        }

    /** A repeated order answers again, with the new order id: the earlier reply may have been lost on its way. */
    private fun answerExisting(command: CreateInvoiceFromQuoteCommand, invoiceId: Long, invoiceNumber: String): InvoiceFromQuoteResult {
        replies.created(command, invoiceId, invoiceNumber)
        return InvoiceFromQuoteResult.Created(invoiceId, invoiceNumber, alreadyExisted = true)
    }

    private fun draft(command: CreateInvoiceFromQuoteCommand): InvoiceFromQuoteResult =
        try {
            val prepared = reader.read(command)
            val draft = writer.write(prepared)
            events.fireAsync(InvoiceCreatedFromQuote(draft.invoiceId, command.quoteNumber, draft.invoiceNumber, prepared.skippedAttachments))
            InvoiceFromQuoteResult.Created(draft.invoiceId, draft.invoiceNumber, alreadyExisted = false)
        } catch (e: QuoteNotFoundException) {
            reject(command, "Das Angebot ist im Angebotsdienst nicht mehr vorhanden: ${e.message}")
        } catch (e: QuoteInvoiceRejectedException) {
            reject(command, e.reason)
        } catch (e: NoActiveNumberRangeException) {
            reject(command, e.message ?: "Es ist kein aktiver Nummernkreis für Rechnungen vorhanden.")
        } catch (e: InvoiceDomainException) {
            reject(command, e.message ?: "Fachlicher Fehler beim Erstellen des Rechnungsentwurfs.")
        } catch (e: ApplicationValidationException) {
            reject(command, e.message ?: "Ein Beleg konnte nicht übernommen werden.")
        } catch (e: ReceiptCountLimitExceededException) {
            reject(command, "Zu viele Belege für eine Rechnung: ${e.message}")
        }

    private fun reject(command: CreateInvoiceFromQuoteCommand, reason: String): InvoiceFromQuoteResult {
        replies.rejected(command, reason)
        events.fireAsync(InvoiceCreationFromQuoteRejected(command.quoteNumber, reason))
        return InvoiceFromQuoteResult.Rejected(reason)
    }
}
