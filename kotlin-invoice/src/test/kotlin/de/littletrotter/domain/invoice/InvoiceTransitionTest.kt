package de.littletrotter.domain.invoice

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import de.littletrotter.fixture.edit

class InvoiceTransitionTest {

    @Test
    fun `finalize emits status and finalized events`() {
        val invoice = Invoice()
        invoice.edit {
            id = 1L
            invoiceNumber = InvoiceNumber("DEV-2026-0001")
        }
        invoice.forceStatusForTest(InvoiceStatus.DRAFT)

        // follow allowed transitions: DRAFT -> IN_REVIEW -> ISSUED
        invoice.transitionTo(InvoiceStatus.IN_REVIEW)
        invoice.transitionTo(InvoiceStatus.ISSUED)

        assertEquals(InvoiceStatus.ISSUED, invoice.status)
        val events = invoice.pullDomainEvents()
        // there should be at least one status-changed event and one finalized event
        assertTrue(events.any { it is de.littletrotter.domain.event.InvoiceStatusChanged })
        assertTrue(events.any { it is de.littletrotter.domain.event.InvoiceFinalized })
    }
}
