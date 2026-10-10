package de.littletrotter.infrastructure.messaging.acl

import de.littletrotter.application.invoice.CreateInvoiceFromQuoteCommand
import de.littletrotter.infrastructure.messaging.OutboxAppended
import de.littletrotter.infrastructure.messaging.OutboxRepository
import de.littletrotter.shared.quote.InvoiceCreatedFromQuoteEvent
import de.littletrotter.shared.quote.InvoiceCreationFromQuoteRejectedEvent
import de.littletrotter.shared.quote.QuoteSubjects
import de.littletrotter.shared.time.TimeSource
import jakarta.enterprise.event.Event
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class QuoteReplyOutboxPublisherTest {

    private val outbox: OutboxRepository = mock()
    private val appended: Event<OutboxAppended> = mock()
    private val time: TimeSource = mock()
    private val publisher = QuoteReplyOutboxPublisher(outbox, appended, time)
    private val now = Instant.parse("2026-10-06T09:00:00Z")
    private val command = CreateInvoiceFromQuoteCommand(
        quoteId = UUID.randomUUID(),
        quoteNumber = "AN-1",
        acceptedOn = LocalDate.of(2026, 10, 5),
        customerOrderReference = null,
        actor = "clerk",
        orderId = UUID.randomUUID(),
    )

    @Test
    fun `a created reply is appended on the created subject and announced`() {
        whenever(time.instant()).thenReturn(now)

        publisher.created(command, 42L, "RE-2026-0001")

        val payload = argumentCaptor<String>()
        verify(outbox).append(eq("QUOTE"), eq(command.quoteId.toString()), eq(QuoteSubjects.INVOICE_CREATED), payload.capture())
        val decoded = InvoiceCreatedFromQuoteEvent.decode(payload.firstValue.toByteArray())
        assertEquals(command.orderId, decoded.eventId)
        assertEquals(command.quoteId, decoded.quoteId)
        assertEquals("AN-1", decoded.quoteNumber)
        assertEquals(42L, decoded.invoiceId)
        assertEquals("RE-2026-0001", decoded.invoiceNumber)
        assertEquals(now, decoded.occurredAt)
        verify(appended).fire(org.mockito.kotlin.any())
    }

    @Test
    fun `a rejected reply carries the reason on the rejected subject`() {
        whenever(time.instant()).thenReturn(now)

        publisher.rejected(command, "Kein Nummernkreis")

        val payload = argumentCaptor<String>()
        verify(outbox).append(eq("QUOTE"), eq(command.quoteId.toString()), eq(QuoteSubjects.INVOICE_REJECTED), payload.capture())
        val decoded = InvoiceCreationFromQuoteRejectedEvent.decode(payload.firstValue.toByteArray())
        assertEquals("Kein Nummernkreis", decoded.reason)
        assertEquals(command.orderId, decoded.eventId)
        verify(appended).fire(org.mockito.kotlin.any())
    }
}
