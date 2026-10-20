package de.littletrotter.domain.invoice

import de.littletrotter.fixture.edit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import de.littletrotter.domain.event.InvoiceStatusChanged
import de.littletrotter.domain.event.InvoiceFinalized
import de.littletrotter.domain.event.InvoiceItemAdded
import de.littletrotter.domain.event.InvoiceItemRemoved
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceItem

class InvoiceLifecycleTest {

    @Test
    fun `transitioning through lifecycle emits status events and finalized event`() {
        val invoice = anInvoice(id = 1L, invoiceNumber = InvoiceNumber("INV-2026-04-00001"))

        // transition DRAFT -> IN_REVIEW
        invoice.transitionTo(InvoiceStatus.IN_REVIEW)
        assertEquals(InvoiceStatus.IN_REVIEW, invoice.status)

        // transition IN_REVIEW -> ISSUED
        invoice.transitionTo(InvoiceStatus.ISSUED)
        assertEquals(InvoiceStatus.ISSUED, invoice.status)

        val events = invoice.pullDomainEvents()
        // Expect two InvoiceStatusChanged events and one InvoiceFinalized
        assertTrue(events.any { it is InvoiceStatusChanged })
        assertTrue(events.any { it is InvoiceFinalized })

        // After pulling, internal buffer should be cleared
        val afterPull = invoice.pullDomainEvents()
        assertTrue(afterPull.isEmpty())
    }

    @Test
    fun `invalid transition throws exception`() {
        val invoice = anInvoice(id = 2L, invoiceNumber = InvoiceNumber("INV-2026-04-00002"))

        // Move to IN_REVIEW then ISSUED
        invoice.transitionTo(InvoiceStatus.IN_REVIEW)
        invoice.transitionTo(InvoiceStatus.ISSUED)

        // Now any further transition is invalid
        val ex = assertThrows(de.littletrotter.domain.exception.InvoiceStatusTransitionException::class.java) {
            invoice.transitionTo(InvoiceStatus.DRAFT)
        }
        assertTrue(ex.message?.contains("Cannot transition") == true)
    }

    @Test
    fun `adding and removing items emits item lifecycle events`() {
        val invoice = anInvoice(id = 3L, invoiceNumber = InvoiceNumber("INV-2026-04-00003"))
        val item = anInvoiceItem(
            description = "Consulting",
            quantity = java.math.BigDecimal.ONE,
            unit = de.littletrotter.shared.catalogue.InvoiceItemUnit.HOUR,
            unitPriceNet = de.littletrotter.shared.Money.of("100.00"),
            taxRateRef = TaxRate().edit { rate = TaxRatePercent(java.math.BigDecimal("19")) },
        )

        invoice.addItem(item)
        invoice.removeItem(item)

        val events = invoice.pullDomainEvents()
        assertTrue(events.any { it is InvoiceItemAdded })
        assertTrue(events.any { it is InvoiceItemRemoved })
    }
}
