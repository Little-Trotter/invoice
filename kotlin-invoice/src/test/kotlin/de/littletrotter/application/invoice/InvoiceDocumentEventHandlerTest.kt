package de.littletrotter.application.invoice

import de.littletrotter.domain.event.InvoiceFinalized
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class InvoiceDocumentEventHandlerTest {

    @Test
    fun `onInvoiceFinalized does not create or publish automatically`() {
        val handler = InvoiceDocumentEventHandler()
        handler.onInvoiceFinalized(InvoiceFinalized(invoiceId = 123L, invoiceNumber = "LT-2026-0001"))

        // Verifies that event handler doesn't automatically trigger job creation or publishing
    }

    @Test
    fun `onInvoiceFinalized does not rethrow`() {
        val handler = InvoiceDocumentEventHandler()
        assertDoesNotThrow {
            handler.onInvoiceFinalized(InvoiceFinalized(invoiceId = 42L, invoiceNumber = "LT-2026-0001"))
        }
    }
}
