package de.littletrotter.infrastructure.messaging

import de.littletrotter.application.dto.InvoiceFromQuoteResult
import de.littletrotter.application.invoice.CreateInvoiceFromQuoteCommand
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceFromQuoteUseCase
import de.littletrotter.domain.event.DomainEvent
import de.littletrotter.domain.event.InvoiceCreationFromQuoteRejected
import de.littletrotter.shared.quote.QuoteInvoiceOrderRequested
import de.littletrotter.shared.quote.QuoteContractViolationException
import jakarta.enterprise.event.Event
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class QuoteInvoiceOrderConsumerTest {

    private val useCase: CreateInvoiceFromQuoteUseCase = mock()
    private val events: Event<DomainEvent> = mock()
    private val consumer = QuoteInvoiceOrderConsumer(useCase, events)

    private val event = QuoteInvoiceOrderRequested(
        orderId = UUID.randomUUID(),
        quoteId = UUID.randomUUID(),
        quoteNumber = "AN-2026-0007",
        acceptedOn = LocalDate.of(2026, 10, 5),
        customerOrderReference = "PO-1",
        deliveryDate = null,
        servicePeriodFrom = null,
        servicePeriodTo = null,
        actor = "clerk",
        occurredAt = Instant.parse("2026-10-05T08:00:00Z"),
    )

    @Test
    fun `a readable acceptance is translated and handed to the use case`() {
        val result = InvoiceFromQuoteResult.Created(1L, "RE-1", alreadyExisted = false)
        whenever(useCase.create(any())).thenReturn(result)

        val answered = consumer.consume(QuoteInvoiceOrderRequested.encode(event))

        assertEquals(result, answered)
        val command = argumentCaptor<CreateInvoiceFromQuoteCommand>()
        verify(useCase).create(command.capture())
        assertEquals(event.quoteId, command.firstValue.quoteId)
        assertEquals("clerk", command.firstValue.actor)
    }

    @Test
    fun `an unreadable payload propagates as a contract violation`() {
        assertThrows(QuoteContractViolationException::class.java) { consumer.consume("{}".toByteArray()) }
    }

    @Test
    fun `a parked acceptance names its offer in the message center event`() {
        consumer.reportParked(QuoteInvoiceOrderRequested.encode(event))

        val fired = argumentCaptor<DomainEvent>()
        verify(events).fireAsync(fired.capture())
        assertEquals(InvoiceCreationFromQuoteRejected("AN-2026-0007", "Verarbeitung mehrfach fehlgeschlagen"), fired.firstValue)
    }

    @Test
    fun `a parked unreadable payload is reported with an unknown offer`() {
        consumer.reportParked("garbage".toByteArray())

        val fired = argumentCaptor<DomainEvent>()
        verify(events).fireAsync(fired.capture())
        assertEquals(InvoiceCreationFromQuoteRejected("unbekannt", "Verarbeitung mehrfach fehlgeschlagen"), fired.firstValue)
    }
}
