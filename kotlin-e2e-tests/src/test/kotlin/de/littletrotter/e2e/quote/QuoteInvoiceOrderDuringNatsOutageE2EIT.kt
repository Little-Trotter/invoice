package de.littletrotter.e2e.quote

import de.littletrotter.application.quote.port.inbound.QuoteInvoiceOrderRequest
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.e2e.ContainerFaults
import de.littletrotter.e2e.EmailAppTestResource
import de.littletrotter.e2e.NatsTestResource
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.QuoteAppTestResource
import de.littletrotter.e2e.QuoteMailModuleTestProfile
import de.littletrotter.e2e.S3TestResource
import io.nats.client.Nats
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/**
 * A clerk orders the invoice of an offer at the moment the message broker is down, for example
 * during a restart of the server. The order is the clerk's decision and must not be lost or doubled:
 * the offer service keeps the message in its own outbox, hands it to the broker as soon as the
 * connection is back, and the invoicing side then drafts exactly one invoice and the offer becomes
 * `INVOICED`. The broker container is stopped and started again by the daemon (same host port), so
 * both applications have to find their way back on their own. Synthetic data only.
 */
@QuarkusTest
@TestProfile(QuoteMailModuleTestProfile::class)
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(S3TestResource::class)
@QuarkusTestResource(NatsTestResource::class)
@QuarkusTestResource(QuoteAppTestResource::class, restrictToAnnotatedClass = true)
@QuarkusTestResource(EmailAppTestResource::class, restrictToAnnotatedClass = true)
class QuoteInvoiceOrderDuringNatsOutageE2EIT : QuoteOfferE2ESupport() {

    @Test
    fun `an invoice ordered while the broker is down becomes one invoice draft after the broker is back`() {
        val offer = sentOffer("Beratung", "1.00", "250.00")
        val natsId = ContainerFaults.containerId(ContainerFaults.NATS_CONTAINER_ID_PROPERTY)

        ContainerFaults.stop(natsId)
        try {
            requestInvoice.request(offer.quoteId, QuoteInvoiceOrderRequest(LocalDate.now(), "BESTELLUNG-${UUID.randomUUID().toString().take(SUFFIX_LENGTH)}", null, null, null))
            Thread.sleep(OUTAGE_MILLIS)
            assertNull(findInvoice(offer.quoteId), "nothing can be drafted while the broker is down")
            assertEquals(QuoteStatus.ACCEPTED, detail.find(offer.quoteId).status, "the order is recorded in the offer service despite the outage")
        } finally {
            ContainerFaults.start(natsId)
        }
        awaitBrokerReady()

        awaitCondition("offer ${offer.number} becomes INVOICED after the broker is back") { detail.find(offer.quoteId).status == QuoteStatus.INVOICED }
        assertEquals(1L, invoiceCountForOffer(offer.quoteId), "exactly one invoice for the ordered offer")
        assertEquals(InvoiceStatus.DRAFT, requireNotNull(findInvoice(offer.quoteId)).status)
    }

    /** The restarted server accepts clients before the test continues; the applications reconnect by themselves. */
    private fun awaitBrokerReady() {
        val deadline = System.nanoTime() + Duration.ofSeconds(BROKER_TIMEOUT_SECONDS).toNanos()
        while (System.nanoTime() < deadline) {
            try {
                Nats.connect(config("nats.url")).use { it.jetStreamManagement().streamNames }
                return
            } catch (_: IOException) {
                Thread.sleep(POLL_MILLIS)
            }
        }
        error("the broker did not come back within $BROKER_TIMEOUT_SECONDS s")
    }

    private companion object {
        const val OUTAGE_MILLIS = 3_000L
        const val BROKER_TIMEOUT_SECONDS = 30L
    }
}
