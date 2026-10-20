package de.littletrotter.domain.invoice

import de.littletrotter.domain.exception.InvoiceValidationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class InvoiceValidationFailureTest {

    @Test
    fun `missing invoice number throws`() {
        val invoice = Invoice()
        // invoiceNumber is unassigned by default -> validate should throw before other checks
        assertThrows<InvoiceValidationException> { invoice.validate() }
    }
}
