package de.littletrotter.infrastructure.messaging.acl

import de.littletrotter.shared.quote.QuoteInvoiceOrderRequested
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class QuoteInvoiceOrderTranslatorTest {

    private fun event(
        orderReference: String?,
        deliveryDate: LocalDate? = null,
        from: LocalDate? = null,
        to: LocalDate? = null,
    ) = QuoteInvoiceOrderRequested(
        orderId = UUID.randomUUID(),
        quoteId = UUID.randomUUID(),
        quoteNumber = "AN-2026-0007",
        acceptedOn = LocalDate.of(2026, 10, 5),
        customerOrderReference = orderReference,
        deliveryDate = deliveryDate,
        servicePeriodFrom = from,
        servicePeriodTo = to,
        actor = "clerk",
        occurredAt = Instant.parse("2026-10-05T08:00:00Z"),
    )

    @Test
    fun `every field of the order reaches the command`() {
        val event = event("PO-9")

        val command = QuoteInvoiceOrderTranslator.toCommand(event)

        assertEquals(event.quoteId, command.quoteId)
        assertEquals("AN-2026-0007", command.quoteNumber)
        assertEquals(LocalDate.of(2026, 10, 5), command.acceptedOn)
        assertEquals("PO-9", command.customerOrderReference)
        assertEquals("clerk", command.actor)
        assertEquals(event.orderId, command.orderId)
    }

    @Test
    fun `a missing order reference stays missing`() {
        assertNull(QuoteInvoiceOrderTranslator.toCommand(event(null)).customerOrderReference)
    }

    @Test
    fun `delivery date and service period reach the command`() {
        val delivery = QuoteInvoiceOrderTranslator.toCommand(event("PO", deliveryDate = LocalDate.of(2026, 9, 30)))
        val period = QuoteInvoiceOrderTranslator.toCommand(event("PO", from = LocalDate.of(2026, 9, 1), to = LocalDate.of(2026, 9, 30)))

        assertEquals(LocalDate.of(2026, 9, 30), delivery.deliveryDate)
        assertNull(delivery.servicePeriodFrom)
        assertEquals(LocalDate.of(2026, 9, 1), period.servicePeriodFrom)
        assertEquals(LocalDate.of(2026, 9, 30), period.servicePeriodTo)
        assertNull(period.deliveryDate)
    }
}
