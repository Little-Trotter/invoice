package de.littletrotter.domain.invoice

import de.littletrotter.fixture.edit
import de.littletrotter.shared.Money
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.PersistenceEmulation

class InvoiceTotalsValidationTest {

    @Test
    fun `recalculateTotals aggregates item amounts correctly`() {
        val invoice = anInvoice(id = 10L, invoiceNumber = InvoiceNumber("INV-2026-04-00010"))

        val item1 = anInvoiceItem(
            quantity = BigDecimal("2"),
            unitPriceNet = Money(BigDecimal("10.00")),
            taxRateRef = TaxRate().edit { rate = TaxRatePercent(BigDecimal("19")) },
            positionId = "1",
            description = "Service A",
        )
        val item2 = anInvoiceItem(
            quantity = BigDecimal("1"),
            unitPriceNet = Money(BigDecimal("5.00")),
            taxRateRef = TaxRate().edit { rate = TaxRatePercent(BigDecimal("7")) },
            positionId = "2",
            description = "Service B",
        )

        invoice.addItem(item1)
        invoice.addItem(item2)

        // calculate amounts per item and invoice totals
        invoice.recalculateTotals()

        // item amounts (numeric compare to avoid BigDecimal scale mismatch)
        assertEquals(0, item1.netAmount.amount.compareTo(BigDecimal("20.0000")))
        // VAT: 20 * 19% = 3.80 -> scale logic may keep 2 decimals
        assertEquals(0, item1.vatAmount.amount.compareTo(BigDecimal("3.80")))

        assertEquals(0, item2.netAmount.amount.compareTo(BigDecimal("5.0000")))
        assertEquals(0, item2.vatAmount.amount.compareTo(BigDecimal("0.35")))

        // invoice totals (rounded to 2 decimals per recalculateTotals)
        assertEquals(0, invoice.totals.netAmount.amount.compareTo(BigDecimal("25.00")))
        // vatAmount = 3.80 + 0.35 = 4.15
        assertEquals(0, invoice.totals.vatAmount.amount.compareTo(BigDecimal("4.15")))
        assertEquals(0, invoice.totals.grossAmount.amount.compareTo(BigDecimal("29.15")))
    }

    @Test
    fun `validate throws when totals mismatch`() {
        val invoice = anInvoice(id = 11L, invoiceNumber = InvoiceNumber("INV-2026-04-00011"))

        val item = anInvoiceItem(
            quantity = BigDecimal.ONE,
            unitPriceNet = Money(BigDecimal("10.00")),
            taxRateRef = TaxRate().edit { rate = TaxRatePercent(BigDecimal("19")) },
            positionId = "1",
            description = "Service A",
        )
        invoice.addItem(item)
        invoice.recalculateTotals()

        // tamper with invoice totals to simulate mismatch
        PersistenceEmulation.assignTotals(invoice, InvoiceTotals(Money(BigDecimal("9.00")), invoice.totals.vatAmount, invoice.totals.grossAmount))

        val ex = assertThrows(InvoiceValidationException::class.java) {
            invoice.validate()
        }
        assertNotNull(ex.message) // message content tested elsewhere; here ensure an error is present
    }
}
