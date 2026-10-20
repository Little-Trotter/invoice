package de.littletrotter.domain.invoice

import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.Money
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.edit
import de.littletrotter.fixture.anInvoice

class InvoiceCalculationEdgeCasesTest {

    private fun bd(v: String) = BigDecimal(v)

    private fun baseInvoice(): Invoice = anInvoice().edit {
        // Use a valid invoice number format for tests
        invoiceNumber = InvoiceNumber("TEST-2026-0001")
        // B1 — fully valid master data, so invoice.validate() (used by the negative-price tests
        // below) reaches the totals check under test instead of failing on unrelated master data.
        senderProfile = aSenderProfile {
            name = "Sender"
            invoicePrefix = "EDGE-"
            email = Email.ofNullable("billing@sender.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban.ofNullable("DE02120300000000202051")
        }
        recipient = aRecipient {
            name = "Recipient"
            customerNumber = CustomerNumber("C-001")
            email = Email.ofNullable("ap@recipient.example")
            customerType = CustomerType.CONSUMER
        }
    }

    private fun assertAmountsConsistent(netStr: String, vatStr: String, grossStr: String) {
        val net = bd(netStr)
        val vat = bd(vatStr)
        val gross = bd(grossStr)
        val diff = net.add(vat).subtract(gross).abs()
        assertEquals(BigDecimal.ZERO.setScale(diff.scale()), diff, "net + vat should equal gross exactly")
    }

    @Test
    fun `zero quantity yields zero amounts`() {
        val invoice = baseInvoice()
        val item = anInvoiceItem(
            quantity = BigDecimal.ZERO,
            unitPriceNet = Money.of("10.00"),
            taxRateRef = TaxRate().edit { rate = TaxRatePercent(BigDecimal("19")) },
        )
        invoice.addItem(item)
        invoice.recalculateTotals()

        assertEquals(0, bd(item.netAmount.toString()).compareTo(BigDecimal.ZERO))
        assertEquals(0, bd(item.vatAmount.toString()).compareTo(BigDecimal.ZERO))
        assertEquals(0, bd(item.grossAmount.toString()).compareTo(BigDecimal.ZERO))

        assertAmountsConsistent(invoice.totals.netAmount.toString(), invoice.totals.vatAmount.toString(), invoice.totals.grossAmount.toString())
    }

    @Test
    fun `zero unit price yields zero amounts`() {
        val invoice = baseInvoice()
        val item = anInvoiceItem(
            quantity = BigDecimal.ONE,
            unitPriceNet = Money.of("0.00"),
            taxRateRef = TaxRate().edit { rate = TaxRatePercent(BigDecimal("19")) },
        )
        invoice.addItem(item)
        invoice.recalculateTotals()

        assertEquals(0, bd(item.netAmount.toString()).compareTo(BigDecimal.ZERO))
        assertAmountsConsistent(item.netAmount.toString(), item.vatAmount.toString(), item.grossAmount.toString())
    }

    @Test
    fun `zero percent tax results in zero vat`() {
        val invoice = baseInvoice()
        val item = anInvoiceItem(
            quantity = BigDecimal.ONE,
            unitPriceNet = Money.of("12.34"),
            taxRateRef = TaxRate().edit { rate = TaxRatePercent(BigDecimal("0")) },
        )
        invoice.addItem(item)
        invoice.recalculateTotals()

        assertEquals(0, bd(item.vatAmount.toString()).compareTo(BigDecimal.ZERO))
        assertAmountsConsistent(item.netAmount.toString(), item.vatAmount.toString(), item.grossAmount.toString())
    }

    @Test
    fun `reduced tax rate seven percent rounding`() {
        val invoice = baseInvoice()
        val item = anInvoiceItem(
            quantity = BigDecimal.ONE,
            unitPriceNet = Money.of("1.11"),
            taxRateRef = TaxRate().edit { rate = TaxRatePercent(BigDecimal("7")) },
        )
        invoice.addItem(item)
        invoice.recalculateTotals()

        val net = bd(item.netAmount.toString())
        val expectedVat = net.multiply(bd("7")).divide(bd("100"), 2, RoundingMode.HALF_UP)
        assertEquals(0, bd(item.vatAmount.toString()).compareTo(expectedVat))
        assertAmountsConsistent(item.netAmount.toString(), item.vatAmount.toString(), item.grossAmount.toString())
    }

    @Test
    fun `half cent boundary rounding behaves deterministically`() {
        val invoice = baseInvoice()
        val item = anInvoiceItem(
            quantity = BigDecimal.ONE,
            unitPriceNet = Money.of("0.05"),
            taxRateRef = TaxRate().edit { rate = TaxRatePercent(BigDecimal("19")) },
        )
        invoice.addItem(item)
        invoice.recalculateTotals()

        val vat = bd(item.vatAmount.toString())
        assertTrue(vat >= bd("0.00"))
        assertAmountsConsistent(item.netAmount.toString(), item.vatAmount.toString(), item.grossAmount.toString())
    }

    @Test
    fun `high precision unit price is rounded to currency scale before calculation`() {
        val invoice = baseInvoice()
        val item = anInvoiceItem().edit {
            quantity = BigDecimal("3")
            // Money.of() rounds 0.3333 -> 0.33 immediately (task A: a unit price is stored and
            // used at 2 decimals, so what's printed always matches what's calculated).
            // 3 x 0.33 = 0.99 - not 3 x 0.3333 = 0.9999 rounded afterwards to 1.00.
            unitPriceNet = Money.of("0.3333")
            taxRateRef = TaxRate().edit { rate = TaxRatePercent(BigDecimal("19")) }
        }
        invoice.addItem(item)
        invoice.recalculateTotals()

        assertEquals(bd("0.99"), item.netAmount.amount)
        assertEquals(bd("0.19"), item.vatAmount.amount)
        assertEquals(bd("1.18"), item.grossAmount.amount)
        assertAmountsConsistent(item.netAmount.toString(), item.vatAmount.toString(), item.grossAmount.toString())
    }

    @Test
    fun `many small items accumulate rounding`() {
        val invoice = baseInvoice()
        repeat(25) {
            val item = anInvoiceItem(
                quantity = BigDecimal.ONE,
                unitPriceNet = Money.of("0.10"),
                taxRateRef = TaxRate().edit { rate = TaxRatePercent(BigDecimal("19")) },
            )
            invoice.addItem(item)
        }
        invoice.recalculateTotals()

        assertAmountsConsistent(invoice.totals.netAmount.toString(), invoice.totals.vatAmount.toString(), invoice.totals.grossAmount.toString())
    }

    // B1 — the item itself is allowed to carry a negative price now (discount position); a
    // *single*-item invoice with a negative price still throws, but for a different reason than
    // before: the aggregated invoice total goes negative (NET_AMOUNT_NEGATIVE), not the item's
    // own price check (which B1 removed).
    @Test
    fun `single negative-price item still throws via negative invoice totals`() {
        val invoice = baseInvoice()
        val item = anInvoiceItem(
            quantity = BigDecimal.ONE,
            unitPriceNet = Money.of("-1.00"),
            taxRateRef = TaxRate().edit { rate = TaxRatePercent(BigDecimal("19")) },
        )
        invoice.addItem(item)
        invoice.recalculateTotals()

        val ex = assertThrows(InvoiceValidationException::class.java) { invoice.validate() }
        assertEquals(de.littletrotter.domain.exception.InvoiceValidationError.NET_AMOUNT_NEGATIVE, ex.error)
    }

    // B1 — negative unitPriceNet x positive quantity must round exactly like the positive case,
    // just with the sign carried through net/vat/gross (Money.percentage() preserves sign).
    @Test
    fun `negative unit price rounds like the positive case with sign preserved`() {
        val item = anInvoiceItem(
            quantity = BigDecimal("3"),
            unitPriceNet = Money.of("0.3333"),
            taxRateRef = TaxRate().edit { rate = TaxRatePercent(BigDecimal("19")) },
            positionId = "1",
            description = "Rabatt",
        )
        val negative = anInvoiceItem(
            quantity = BigDecimal("3"),
            unitPriceNet = Money.of("-0.3333"),
            taxRateRef = TaxRate().edit { rate = TaxRatePercent(BigDecimal("19")) },
            positionId = "2",
            description = "Rabatt",
        )
        item.calculateAmounts()
        negative.calculateAmounts()

        assertEquals(item.netAmount.amount, negative.netAmount.amount.negate())
        assertEquals(item.vatAmount.amount, negative.vatAmount.amount.negate())
        assertEquals(item.grossAmount.amount, negative.grossAmount.amount.negate())
        assertEquals(bd("-0.99"), negative.netAmount.amount)
        assertEquals(bd("-0.19"), negative.vatAmount.amount)
        assertEquals(bd("-1.18"), negative.grossAmount.amount)
    }

    @Test
    fun `very small price rounds to zero`() {
        val invoice = baseInvoice()
        val item = anInvoiceItem(
            quantity = BigDecimal.ONE,
            unitPriceNet = Money.of("0.001"),
            taxRateRef = TaxRate().edit { rate = TaxRatePercent(BigDecimal("19")) },
        )
        invoice.addItem(item)
        invoice.recalculateTotals()

        assertEquals(bd("0.00"), item.netAmount.amount)
        assertEquals(bd("0.00"), item.vatAmount.amount)
        assertEquals(bd("0.00"), item.grossAmount.amount)
        assertAmountsConsistent(item.netAmount.toString(), item.vatAmount.toString(), item.grossAmount.toString())
    }

    @Test
    fun `calculateAmounts requires tax rate reference`() {
        val item = anInvoiceItem(
            quantity = BigDecimal.ONE,
            unitPriceNet = Money.of("10.00"),
            positionId = "P-1",
            description = "Item",
            taxRateRef = null,
        )

        assertThrows(InvoiceValidationException::class.java) { item.calculateAmounts() }
    }

    @Test
    fun `mixed tax rates aggregate correctly`() {
        val invoice = baseInvoice()
        val a = anInvoiceItem(
            quantity = BigDecimal.ONE,
            unitPriceNet = Money.of("1.00"),
            taxRateRef = TaxRate().edit { rate = TaxRatePercent(BigDecimal("19")) },
        )
        val b = anInvoiceItem(
            quantity = BigDecimal.ONE,
            unitPriceNet = Money.of("1.00"),
            taxRateRef = TaxRate().edit { rate = TaxRatePercent(BigDecimal("7")) },
        )
        invoice.addItem(a)
        invoice.addItem(b)
        invoice.recalculateTotals()

        assertAmountsConsistent(invoice.totals.netAmount.toString(), invoice.totals.vatAmount.toString(), invoice.totals.grossAmount.toString())
    }
}
