package de.littletrotter.e2e.quote

import de.littletrotter.application.quote.port.inbound.QuoteInvoiceOrderRequest
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.e2e.EmailAppTestResource
import de.littletrotter.e2e.NatsTestResource
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.QuoteAppTestResource
import de.littletrotter.e2e.QuoteMailModuleTestProfile
import de.littletrotter.e2e.S3TestResource
import de.littletrotter.shared.NatsStreamSetup
import de.littletrotter.shared.quote.QuoteInvoiceOrderRequested
import de.littletrotter.shared.quote.QuoteStreams
import de.littletrotter.shared.quote.QuoteSubjects
import io.nats.client.JetStreamManagement
import io.nats.client.Nats
import io.nats.client.PublishOptions
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/**
 * The broker delivers at least once, so the same invoice order can reach the invoicing side more than
 * once, for example when the offer service republishes after a crash. One ordered offer must never
 * become two invoices. Two cases: a republish with the same broker message id (the order id), which the stream
 * drops itself, and a delivery of the same order under a new message id, which only the consumer can
 * recognise. Either way there is one invoice, the repeated delivery is acknowledged rather than
 * retried, and nothing lands in the dead-letter stream. Synthetic data only.
 */
@QuarkusTest
@TestProfile(QuoteMailModuleTestProfile::class)
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(S3TestResource::class)
@QuarkusTestResource(NatsTestResource::class)
@QuarkusTestResource(QuoteAppTestResource::class, restrictToAnnotatedClass = true)
@QuarkusTestResource(EmailAppTestResource::class, restrictToAnnotatedClass = true)
class QuoteInvoiceOrderDuplicateDeliveryE2EIT : QuoteOfferE2ESupport() {

    @Test
    fun `the same order delivered again creates no second invoice and parks nothing`() {
        val offer = sentOffer("Beratung", "1.00", "80.00")
        requestInvoice.request(offer.quoteId, QuoteInvoiceOrderRequest(LocalDate.now(), "BESTELLUNG-${UUID.randomUUID().toString().take(SUFFIX_LENGTH)}", null, null, null))
        awaitCondition("offer ${offer.number} becomes INVOICED") { detail.find(offer.quoteId).status == QuoteStatus.INVOICED }
        val invoice = requireNotNull(findInvoice(offer.quoteId)) { "an invoice exists for the offer" }

        Nats.connect(config("nats.url")).use { connection ->
            val management = connection.jetStreamManagement()
            val original = management.getLastMessage(QuoteStreams.INVOICE_ORDER, QuoteSubjects.INVOICE_ORDER_REQUESTED)
            val event = QuoteInvoiceOrderRequested.decode(original.data)
            assertEquals(offer.quoteId, event.quoteId.toString(), "the last order in the stream is this offer's")
            val streamEnd = management.getStreamInfo(QuoteStreams.INVOICE_ORDER).streamState.lastSequence
            val parkedBefore = parked(management)
            val consumer = config("quote.nats.consumer.invoice-order")
            val redeliveredBefore = management.getConsumerInfo(QuoteStreams.INVOICE_ORDER, consumer).redelivered
            val jetStream = connection.jetStream()

            val sameId = jetStream.publish(QuoteSubjects.INVOICE_ORDER_REQUESTED, original.data, PublishOptions.builder().messageId(event.orderId.toString()).build())
            assertTrue(sameId.isDuplicate, "the stream drops a republish under the same message id")
            assertEquals(streamEnd, management.getStreamInfo(QuoteStreams.INVOICE_ORDER).streamState.lastSequence, "the dropped republish adds nothing to the stream")

            val newId = jetStream.publish(QuoteSubjects.INVOICE_ORDER_REQUESTED, original.data, PublishOptions.builder().messageId(UUID.randomUUID().toString()).build())
            assertEquals(streamEnd + 1, newId.seqno, "the same event under a new message id is stored and delivered again")
            awaitCondition("the consumer acknowledged the repeated delivery") {
                management.getConsumerInfo(QuoteStreams.INVOICE_ORDER, consumer).ackFloor.streamSequence >= newId.seqno
            }
            Thread.sleep(SETTLE_MILLIS)

            val info = management.getConsumerInfo(QuoteStreams.INVOICE_ORDER, consumer)
            assertEquals(0L, info.numAckPending, "the repeated delivery was acknowledged, nothing is waiting")
            assertEquals(redeliveredBefore, info.redelivered, "the repeated delivery was not retried")
            assertEquals(parkedBefore, parked(management), "nothing was archived in the dead-letter stream")
        }

        assertEquals(1L, invoiceCountForOffer(offer.quoteId), "one invoice for the offer")
        assertEquals(invoice.id, requireNotNull(findInvoice(offer.quoteId)).id, "it is still the first invoice")
        assertEquals(QuoteStatus.INVOICED, detail.find(offer.quoteId).status)
    }

    private fun parked(management: JetStreamManagement): Long = management.getStreamInfo(NatsStreamSetup.DLQ_STREAM_NAME).streamState.msgCount
}
