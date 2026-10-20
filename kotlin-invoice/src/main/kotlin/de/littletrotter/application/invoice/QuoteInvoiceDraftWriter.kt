package de.littletrotter.application.invoice

import de.littletrotter.application.invoice.port.outbound.QuoteReplyPublisherPort
import de.littletrotter.application.quote.port.inbound.QuoteB2bValue
import de.littletrotter.application.quote.port.inbound.QuoteDetail
import de.littletrotter.application.reference.B2bFieldValueService
import de.littletrotter.application.shared.SetsAuditGuc
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.requiresReverseChargeDecision
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.domain.repository.SenderProfileRepository
import de.littletrotter.domain.value.VatId
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.shared.b2b.B2bTermBinding
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import java.time.LocalDate
import de.littletrotter.domain.invoice.InvoicePartySnapshot
import de.littletrotter.domain.invoice.headerChange

/** One PDF of the offer that becomes a receipt of the invoice. */
class QuoteReceiptSource(val fileName: String, val description: String, val documentDate: LocalDate, val bytes: ByteArray)

/** Everything read from the offer service before the transaction starts. */
class PreparedQuoteInvoice(
    val command: CreateInvoiceFromQuoteCommand,
    val detail: QuoteDetail,
    val receipts: List<QuoteReceiptSource>,
    val skippedAttachments: List<String>,
)

class QuoteInvoiceDraft(val invoiceId: Long, val invoiceNumber: String)

/** A reason, phrased for the clerk, why the offer cannot become an invoice draft. */
class QuoteInvoiceRejectedException(val reason: String) : RuntimeException(reason)

/**
 * The write half of drafting an invoice from an accepted offer: one short transaction that
 * creates the draft, takes over every offer position, reference and file, numbers it and records
 * the reply for the offer service. Anything that cannot be mapped aborts the whole transaction,
 * so a half-built invoice (or an orphaned receipt) never survives.
 *
 * Split from [CreateInvoiceFromQuote] because the offer service is called over HTTP before this
 * transaction, never inside it, and because a transaction boundary is only honoured across beans.
 */
@ApplicationScoped
class QuoteInvoiceDraftWriter @Inject constructor(
    private val lifecycle: InvoiceLifecycleService,
    private val items: QuoteInvoiceItems,
    private val receipts: QuoteInvoiceReceipts,
    private val senders: SenderProfileRepository,
    private val recipients: RecipientRepository,
    private val b2bValues: B2bFieldValueService,
    private val replies: QuoteReplyPublisherPort,
) {

    @Transactional
    @SetsAuditGuc(action = InvoiceAuditAction.CREATED_FROM_QUOTE)
    fun write(prepared: PreparedQuoteInvoice): QuoteInvoiceDraft {
        val detail = prepared.detail
        QuoteServicePeriodCheck.reject(prepared.command)?.let { reject(it) }
        val sender = senders.findDefault() ?: reject("Es ist kein Standard-Absenderprofil festgelegt.")
        val recipient = detail.party.customerId.toLongOrNull()?.let { recipients.findById(it) }
            ?: reject("Der Kunde '${detail.party.name}' ist in den Stammdaten nicht vorhanden.")
        val b2b = classify(detail.b2bValues)
        val created = lifecycle.create(sender.id!!, recipient.id!!, reverseChargeDecisionFor(detail, sender, recipient))
        val invoiceId = created.id!!

        detail.items.sortedBy { it.position }.forEach { items.add(invoiceId, it, created.invoiceDate) }
        if (b2b.free.isNotEmpty()) b2bValues.saveFieldValues(B2bFieldOwnerType.INVOICE, invoiceId, b2b.free)

        val invoice = checkNotNull(lifecycle.findById(invoiceId)) { "Invoice $invoiceId vanished inside its own transaction" }
        applyQuoteIdentity(invoice, prepared, b2b.bound, Parties(sender, recipient))
        lifecycle.save(invoice)

        receipts.attach(invoiceId, prepared.receipts)
        replies.created(prepared.command, invoiceId, invoice.invoiceNumber.value)
        return QuoteInvoiceDraft(invoiceId, invoice.invoiceNumber.value)
    }

    /**
     * Only a recipient whose VAT id country differs from the seller's forces the question; the
     * offer answers it through its positions' tax categories. Otherwise the header stays as it
     * is, so an offer that merely carries a Reverse Charge position for a domestic customer is
     * not turned into a refusal here.
     */
    private fun reverseChargeDecisionFor(detail: QuoteDetail, sender: SenderProfile, recipient: Recipient): Boolean? {
        val registrationCountries = sender.additionalVatRegistrations.map { it.country }.toSet()
        if (!requiresReverseChargeDecision(sender.vatId?.value, recipient.vatId?.value, registrationCountries)) return null
        return QuoteReverseChargeDecision.derive(detail.items)
            ?: reject("Die Positionen des Angebots mischen Reverse Charge mit anderen Steuerkategorien; die Rechnung kann nicht automatisch erstellt werden.")
    }

    /**
     * The invoice states what the offer said, not what master data says today: buyer name and VAT
     * id come from the offer's party, and the references tie the invoice to the offer and the
     * customer's order.
     */
    private fun applyQuoteIdentity(invoice: Invoice, prepared: PreparedQuoteInvoice, bound: Map<String, String>, parties: Parties) {
        val command = prepared.command
        val detail = prepared.detail
        val buyer = InvoicePartySnapshot.forBuyer(parties.recipient, invoice.references.buyerReference)
        buyer.correctIdentity(detail.party.name, VatId.ofNullable(detail.party.vatId))
        invoice.applyPartySnapshots(InvoicePartySnapshot.forSeller(parties.sender), buyer)
        invoice.linkToQuote(command.quoteId)
        invoice.updateHeader(
            invoice.headerChange().copy(
                deliveryDate = command.deliveryDate,
                servicePeriodFrom = command.servicePeriodFrom,
                servicePeriodTo = command.servicePeriodTo,
            ),
        )
        val references = invoice.references
        references.update(
            references.asChange().copy(
                orderReference = command.quoteNumber,
                orderReferenceDate = command.acceptedOn,
                offerReference = command.quoteNumber,
                offerReferenceDate = detail.quoteDate,
                projectNumber = bound[PROJECT_NUMBER] ?: references.projectNumber,
                purchaseOrderReference = command.customerOrderReference?.takeIf { it.isNotBlank() }
                    ?: bound[PURCHASE_ORDER] ?: references.purchaseOrderReference,
            ),
        )
    }

    /** FREE terms are stored as invoice field values, the two bound ones live on the invoice itself. */
    private fun classify(values: List<QuoteB2bValue>): B2bValues {
        val free = linkedMapOf<String, String>()
        val bound = linkedMapOf<String, String>()
        values.forEach { value ->
            val term = B2bTerm.fromCodeOrNull(value.btCode)
            when {
                term == null || term.binding == null -> reject("Das B2B-Feld BT-${value.btCode} des Angebots ist unbekannt.")
                term.binding == B2bTermBinding.FREE -> free[term.officialCode] = value.value
                term.boundProperty == PROJECT_NUMBER || term.boundProperty == PURCHASE_ORDER -> bound[term.boundProperty!!] = value.value
                else -> reject("Das B2B-Feld ${term.officialCode} des Angebots kann nicht in die Rechnung übernommen werden.")
            }
        }
        return B2bValues(free, bound)
    }

    private fun reject(reason: String): Nothing = throw QuoteInvoiceRejectedException(reason)

    private class B2bValues(val free: Map<String, String>, val bound: Map<String, String>)

    private class Parties(val sender: SenderProfile, val recipient: Recipient)

    private companion object {
        const val PROJECT_NUMBER = "projectNumber"
        const val PURCHASE_ORDER = "purchaseOrderReference"
    }
}
