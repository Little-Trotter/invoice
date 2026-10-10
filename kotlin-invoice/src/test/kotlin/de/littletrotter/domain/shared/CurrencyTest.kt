package de.littletrotter.domain.shared

import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.shared.Currency
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class CurrencyTest {

    @Test
    fun `new invoice defaults to EUR`() {
        // The header default (and V014 NOT NULL DEFAULT 'EUR') keep legacy rows EUR without code.
        val invoice = Invoice()
        assertEquals(Currency.EUR, invoice.currency)
    }

    @Test
    fun `currency can be set to each supported value`() {
        for (c in Currency.entries) {
            val invoice = Invoice().apply { currency = c }
            assertSame(c, invoice.currency)
            assertEquals(c.code, invoice.currency.code)
        }
    }
}
