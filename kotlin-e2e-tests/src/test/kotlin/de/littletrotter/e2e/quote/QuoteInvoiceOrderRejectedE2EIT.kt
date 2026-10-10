package de.littletrotter.e2e.quote

import de.littletrotter.application.quote.port.inbound.QuoteInvoiceOrderRequest
import de.littletrotter.application.quote.port.inbound.QuoteInvoiceOrderStatus
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.e2e.EmailAppTestResource
import de.littletrotter.e2e.NatsTestResource
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.QuoteAppTestResource
import de.littletrotter.e2e.QuoteMailModuleTestProfile
import de.littletrotter.e2e.S3TestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The invoicing side refuses an order it cannot fulfil, here because no default sender profile is
 * set. The offer must then stay `ACCEPTED` (the order was the acceptance), name the reason on the
 * rejected order and create no invoice. Once the cause is fixed, ordering again with a new order id
 * succeeds and the offer becomes `INVOICED`. Synthetic data only.
 */
@QuarkusTest
@TestProfile(QuoteMailModuleTestProfile::class)
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(S3TestResource::class)
@QuarkusTestResource(NatsTestResource::class)
@QuarkusTestResource(QuoteAppTestResource::class, restrictToAnnotatedClass = true)
@QuarkusTestResource(EmailAppTestResource::class, restrictToAnnotatedClass = true)
class QuoteInvoiceOrderRejectedE2EIT : QuoteOfferE2ESupport() {

    @Test
    fun `a refused order is shown with its reason and ordering again leads to the invoice`() {
        val offer = sentOffer("Beratung", "1.00", "90.00")
        withoutDefaultSender()
        val delivery = LocalDate.now().minusDays(1)

        val first = requestInvoice.request(offer.quoteId, QuoteInvoiceOrderRequest(LocalDate.now(), null, delivery, null, null))

        awaitCondition("the order $first is refused") { detail.find(offer.quoteId).invoiceOrder?.status == QuoteInvoiceOrderStatus.REJECTED }
        val refused = requireNotNull(detail.find(offer.quoteId).invoiceOrder)
        assertEquals(first, refused.orderId)
        assertTrue(refused.rejectionReason.orEmpty().contains("Absenderprofil"), "reason: ${refused.rejectionReason}")
        assertEquals(QuoteStatus.ACCEPTED, detail.find(offer.quoteId).status, "a refusal leaves the offer accepted")
        assertNull(findInvoice(offer.quoteId), "a refused order creates no invoice")

        withDefaultSender()
        val second = requestInvoice.request(offer.quoteId, QuoteInvoiceOrderRequest(null, null, delivery, null, null))

        assertNotEquals(first, second, "ordering again is a new order")
        awaitCondition("offer ${offer.number} becomes INVOICED") { detail.find(offer.quoteId).status == QuoteStatus.INVOICED }
        val draft = requireNotNull(findInvoice(offer.quoteId))
        assertEquals(InvoiceStatus.DRAFT, draft.status)
        assertEquals(delivery, draft.deliveryDate)
        assertEquals(1L, invoiceCountForOffer(offer.quoteId))
        assertEquals(second, requireNotNull(detail.find(offer.quoteId).invoiceOrder).orderId)
    }
}
