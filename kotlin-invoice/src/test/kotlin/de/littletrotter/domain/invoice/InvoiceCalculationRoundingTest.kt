package de.littletrotter.domain.invoice

import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit
import de.littletrotter.shared.Money
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceItem

class InvoiceCalculationRoundingTest {

    @Test
    fun `test exact rounding with 19 percent tax`() {
        val invoice = anInvoice(
            invoiceNumber = InvoiceNumber("TEST-2026-0001"),
            senderProfile = validSender(),
            recipient = validRecipient(),
        )

        // 1.23 * 1.19 = 1.4637 -> rounded to 1.46 (gross)
        // net = 1.23, vat = 0.2337 -> 0.23
        val item = anInvoiceItem(
            description = "Test Item",
            quantity = BigDecimal.ONE,
            unitPriceNet = Money.of("1.23"),
            taxRateRef = TaxRate().edit { rate = TaxRatePercent(BigDecimal("19")) },
        )
        invoice.addItem(item)
        invoice.recalculateTotals()

        assertEquals("1.23", item.netAmount.toString())
        assertEquals("0.23", item.vatAmount.toString())
        assertEquals("1.46", item.grossAmount.toString())

        assertEquals("1.23", invoice.totals.netAmount.toString())
        assertEquals("0.23", invoice.totals.vatAmount.toString())
        assertEquals("1.46", invoice.totals.grossAmount.toString())

        // Should validate without exception
        assertDoesNotThrow { invoice.validate() }
    }

    @Test
    fun `test invoice VAT is not recalculated from aggregated net`() {
        val invoice = anInvoice(
            invoiceNumber = InvoiceNumber("TEST-2026-0003"),
            senderProfile = aSenderProfile { name = "Sender" },
            recipient = aRecipient { name = "Recipient"; customerNumber = CustomerNumber("C-001") },
        )

        repeat(3) {
            invoice.addItem(
                anInvoiceItem(
                    description = "Micro Item $it",
                    quantity = BigDecimal.ONE,
                    unitPriceNet = Money.of("0.01"),
                    taxRateRef = TaxRate().edit { rate = TaxRatePercent(BigDecimal("19")) },
                )
            )
        }

        invoice.recalculateTotals()

        // Q1: Money.toString() now keeps two decimals instead of stripping trailing zeros.
        // The amounts themselves are unchanged — only "0" reads as "0.00" now.
        assertEquals("0.03", invoice.totals.netAmount.toString())
        assertEquals("0.00", invoice.totals.vatAmount.toString())
        assertEquals("0.03", invoice.totals.grossAmount.toString())
    }

    @Test
    fun `test accumulation of rounding errors over multiple items`() {
        val invoice = anInvoice(
            invoiceNumber = InvoiceNumber("TEST-2026-0002"),
            senderProfile = validSender(),
            recipient = validRecipient(),
        )

        // Item 1: 10.00 * 0.19 = 1.90
        val item1 = anInvoiceItem(
            quantity = BigDecimal.ONE,
            unitPriceNet = Money.of("10.00"),
            taxRateRef = TaxRate().edit { rate = TaxRatePercent(BigDecimal("19")) },
        )
        // Item 2: 1.23 * 0.19 = 0.2337 -> 0.23
        val item2 = anInvoiceItem(
            quantity = BigDecimal.ONE,
            unitPriceNet = Money.of("1.23"),
            taxRateRef = TaxRate().edit { rate = TaxRatePercent(BigDecimal("19")) },
        )

        invoice.addItem(item1)
        invoice.addItem(item2)
        invoice.recalculateTotals()

        // Sum of Net: 10.00 + 1.23 = 11.23
        // Sum of VAT: 1.90 + 0.23 = 2.13
        // Gross: 11.23 + 2.13 = 13.36

        assertEquals("11.23", invoice.totals.netAmount.toString())
        assertEquals("2.13", invoice.totals.vatAmount.toString())
        assertEquals("13.36", invoice.totals.grossAmount.toString())

        assertDoesNotThrow { invoice.validate() }
    }

    private fun validSender() = aSenderProfile {
        name = "Sender"
        email = Email("billing@sender.example")
        taxNumber = TaxNumber("12/345/67890")
        vatId = VatId("DE123456789")
        iban = Iban("DE89370400440532013000")
    }

    private fun validRecipient() = aRecipient {
        name = "Recipient"
        customerNumber = CustomerNumber("C-001")
        email = Email("customer@recipient.example")
        vatId = VatId("DE987654321")
        iban = Iban("DE02120300000000202051")
    }
}
