package de.littletrotter.e2e.quote

import de.littletrotter.application.quote.port.inbound.QuoteLogKind
import de.littletrotter.application.quote.port.inbound.QuoteInvoiceOrderRequest
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.e2e.EmailAppTestResource
import de.littletrotter.e2e.NatsTestResource
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.QuoteAppTestResource
import de.littletrotter.e2e.QuoteMailModuleTestProfile
import de.littletrotter.e2e.S3TestResource
import de.littletrotter.shared.quote.QuoteInvoiceOrderRequested
import de.littletrotter.shared.quote.QuoteStreams
import de.littletrotter.shared.quote.QuoteSubjects
import io.nats.client.Nats
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * A sent offer becomes an invoice draft on an explicit order: the offer is created, reviewed and
 * dispatched through the real offer and mail modules (as in the dispatch test), then the invoice is
 * ordered through the invoicing port with a delivery date. From there everything is the modules' own
 * code: the offer service's outbox publishes the order, the invoicing consumer reads the offer back
 * over HTTP and drafts the invoice with the offer PDF and the customer attachment as receipts, and
 * its reply sets the offer to `INVOICED`. A plain acceptance, by contrast, creates no invoice.
 *
 * Seam: as in the dispatch test, the document worker is played by the test. Synthetic data only.
 */
@QuarkusTest
@TestProfile(QuoteMailModuleTestProfile::class)
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(S3TestResource::class)
@QuarkusTestResource(NatsTestResource::class)
@QuarkusTestResource(QuoteAppTestResource::class, restrictToAnnotatedClass = true)
@QuarkusTestResource(EmailAppTestResource::class, restrictToAnnotatedClass = true)
class QuoteInvoiceOrderToInvoiceE2EIT : QuoteOfferE2ESupport() {

    @Test
    fun `an ordered offer is invoiced as a draft with receipts and a repeated order changes nothing`() {
        val offer = sentOffer("Beratung", "2.00", "100.00")
        val orderReference = "BESTELLUNG-${UUID.randomUUID().toString().take(SUFFIX_LENGTH)}"

        val deliveryDate = LocalDate.now().minusDays(DELIVERY_OFFSET_DAYS)
        requestInvoice.request(offer.quoteId, QuoteInvoiceOrderRequest(LocalDate.now(), orderReference, deliveryDate, null, null))

        awaitCondition("offer ${offer.number} becomes INVOICED") { detail.find(offer.quoteId).status == QuoteStatus.INVOICED }
        val invoiced = detail.find(offer.quoteId)
        val invoiceNumber = requireNotNull(invoiced.invoiceNumber) { "the offer names its invoice" }
        val invoiceLines = log.log(offer.quoteId, setOf(QuoteLogKind.INVOICE))
        assertEquals(listOf("Rechnungsauftrag erteilt", "Rechnung $invoiceNumber erstellt"), invoiceLines.map { it.text }, "INVOICE lines: $invoiceLines")

        val draft = requireNotNull(findInvoice(offer.quoteId)) { "an invoice exists for the offer" }
        assertEquals(InvoiceStatus.DRAFT, draft.status)
        assertEquals(invoiceNumber, draft.number)
        assertEquals(offer.number, draft.orderReference)
        assertEquals(offer.number, draft.offerReference)
        assertEquals(orderReference, draft.purchaseOrderReference)
        assertEquals(deliveryDate, draft.deliveryDate)
        assertNull(draft.servicePeriodFrom)
        assertNull(draft.servicePeriodTo)
        assertEquals(listOf(offer.description), draft.itemDescriptions)
        assertEquals(0, BigDecimal("2.00").compareTo(draft.quantities.single()))
        assertEquals(0, BigDecimal("100.00").compareTo(draft.netPrices.single()))
        assertEquals(setOf(sha256Hex(offer.dispatchPdf), sha256Hex(offer.attachedPdf)), draft.receiptChecksums.toSet())
        assertEquals(2, draft.receiptChecksums.size)
        assertEquals(1L, journalTransactions(draft.id, CREATED_FROM_QUOTE_ACTION), "the draft was journaled as one CREATED_FROM_QUOTE business action")
        awaitCondition("the message center names the draft") { messageCount("Angebot ${offer.number}: Rechnungsentwurf%") == 1L }

        republishOrder(offer, orderReference)

        assertEquals(draft.id, requireNotNull(findInvoice(offer.quoteId)).id)
        assertEquals(1L, invoiceCountForOffer(offer.quoteId), "a repeated order creates no second invoice")
        assertEquals(QuoteStatus.INVOICED, detail.find(offer.quoteId).status)
        assertEquals(invoiceLines.map { it.text }, log.log(offer.quoteId, setOf(QuoteLogKind.INVOICE)).map { it.text }, "the repeated order adds no INVOICE line")
    }

    /** The same order again under a new order id, so the broker's duplicate window does not hide it from the consumer. */
    private fun republishOrder(offer: SentOffer, orderReference: String) {
        val event = QuoteInvoiceOrderRequested(
            orderId = UUID.randomUUID(),
            quoteId = UUID.fromString(offer.quoteId),
            quoteNumber = offer.number,
            acceptedOn = LocalDate.now(),
            customerOrderReference = orderReference,
            deliveryDate = null,
            servicePeriodFrom = null,
            servicePeriodTo = null,
            actor = "e2e",
            occurredAt = Instant.now(),
        )
        Nats.connect(config("nats.url")).use { connection ->
            val sequence = connection.jetStream().publish(QuoteSubjects.INVOICE_ORDER_REQUESTED, QuoteInvoiceOrderRequested.encode(event)).seqno
            val management = connection.jetStreamManagement()
            val consumer = config("quote.nats.consumer.invoice-order")
            awaitCondition("the consumer acknowledged the repeated order") {
                management.getConsumerInfo(QuoteStreams.INVOICE_ORDER, consumer).ackFloor.streamSequence >= sequence
            }
        }
        // The consumer answers a repeated order with a repeated reply; give the offer service time to (not) act on it.
        Thread.sleep(SETTLE_MILLIS)
    }

    @Test
    fun `plain acceptance records the fact and creates no invoice`() {
        val offer = sentOffer("Beratung", "1.00", "60.00")

        acceptQuote.accept(offer.quoteId, LocalDate.now(), "BESTELLUNG-${UUID.randomUUID().toString().take(SUFFIX_LENGTH)}")

        awaitCondition("offer ${offer.number} is ACCEPTED") { detail.find(offer.quoteId).status == QuoteStatus.ACCEPTED }
        Thread.sleep(SETTLE_MILLIS)
        assertNull(findInvoice(offer.quoteId), "accepting does not draft an invoice")
        assertEquals(0L, invoiceCountForOffer(offer.quoteId))
        assertEquals(QuoteStatus.ACCEPTED, detail.find(offer.quoteId).status)
    }

    private companion object {
        const val DELIVERY_OFFSET_DAYS = 3L
    }
}
