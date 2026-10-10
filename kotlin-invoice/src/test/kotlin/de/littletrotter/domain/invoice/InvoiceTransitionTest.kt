package de.littletrotter.domain.invoice

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class InvoiceTransitionTest {

    @Test
    fun `finalize emits status and finalized events`() {
        val invoice = Invoice()
        invoice.id = 1L
        invoice.invoiceNumber = InvoiceNumber("DEV-2026-0001")
        invoice.forceStatusForTest(InvoiceStatus.DRAFT)

        // follow allowed transitions: DRAFT -> IN_REVIEW -> SENT
        invoice.transitionTo(InvoiceStatus.IN_REVIEW)
        invoice.transitionTo(InvoiceStatus.SENT)

        assertEquals(InvoiceStatus.SENT, invoice.status)
        val events = invoice.pullDomainEvents()
        // there should be at least one status-changed event and one finalized event
        assertTrue(events.any { it is de.littletrotter.domain.event.InvoiceStatusChanged })
        assertTrue(events.any { it is de.littletrotter.domain.event.InvoiceFinalized })
    }
}
