package de.littletrotter.domain.invoice

import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.shared.Money
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.random.Random
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.edit

class InvoiceTotalsPropertyTest {

    /** B1 — fully valid master data, so validate() reaches the totals check under test. */
    private fun validSender() = aSenderProfile {
        id = 1L
        name = "Sender GmbH"
        invoicePrefix = "PROP-"
        email = Email.ofNullable("billing@sender.example")
        taxNumber = TaxNumber("12/345/67890")
        vatId = VatId("DE123456789")
        iban = Iban.ofNullable("DE02120300000000202051")
    }

    private fun validRecipient() = aRecipient {
        id = 2L
        customerNumber = CustomerNumber("PROP-100")
        name = "Acme GmbH"
        email = Email.ofNullable("ap@acme.example")
        customerType = CustomerType.CONSUMER
    }

    @Test
    fun `recalculateTotals sums items correctly under many random inputs`() {
        val rnd = Random(77)
        repeat(200) {
            val invoice = Invoice()
            val itemCount = rnd.nextInt(1, 8)
            repeat(itemCount) {
                val qty = BigDecimal((rnd.nextDouble() * 999.9999) + 0.0001).setScale(4, RoundingMode.HALF_UP)
                val price = BigDecimal((rnd.nextDouble() * 10000.0)).setScale(4, RoundingMode.HALF_UP)
                val rate = BigDecimal(rnd.nextInt(0, 100)).setScale(2, RoundingMode.HALF_UP)

                val item = anInvoiceItem(
                    quantity = qty,
                    unitPriceNet = Money(price),
                    taxRateRef = TaxRate().edit { this.rate = TaxRatePercent(rate) },
                    positionId = "pos",
                    description = "d",
                )
                invoice.addItem(item)
            }

            invoice.recalculateTotals()

            val sumNet = invoice.itemList.fold(BigDecimal.ZERO) { acc, item -> acc.add(item.netAmount.amount) }
            val sumVat = invoice.itemList.fold(BigDecimal.ZERO) { acc, item -> acc.add(item.vatAmount.amount) }
            val sumGross = invoice.itemList.fold(BigDecimal.ZERO) { acc, item -> acc.add(item.grossAmount.amount) }

            val expectedNet = sumNet.setScale(2, RoundingMode.HALF_UP)
            val expectedVat = sumVat.setScale(2, RoundingMode.HALF_UP)
            val expectedGross = sumGross.setScale(2, RoundingMode.HALF_UP)

            assertEquals(0, invoice.totals.netAmount.amount.compareTo(expectedNet), "net mismatch")
            assertEquals(0, invoice.totals.vatAmount.amount.compareTo(expectedVat), "vat mismatch")
            assertEquals(0, invoice.totals.grossAmount.amount.compareTo(expectedGross), "gross mismatch")
            assertEquals(2, invoice.totals.netAmount.amount.scale(), "invoice net scale mismatch")
            assertEquals(2, invoice.totals.vatAmount.amount.scale(), "invoice vat scale mismatch")
            assertEquals(2, invoice.totals.grossAmount.amount.scale(), "invoice gross scale mismatch")
        }
    }

    @Test
    fun `invoice vat total is sum of rounded line vats only`() {
        val invoice = Invoice()
        repeat(3) { index ->
            invoice.addItem(
                anInvoiceItem(
                    positionId = (index + 1).toString(),
                    description = "small-$index",
                    quantity = BigDecimal.ONE,
                    unitPriceNet = Money(BigDecimal("0.01")),
                    taxRateRef = TaxRate().edit { rate = TaxRatePercent(BigDecimal("19")) },
                )
            )
        }

        invoice.recalculateTotals()

        assertEquals(BigDecimal("0.03"), invoice.totals.netAmount.amount)
        assertEquals(BigDecimal("0.00"), invoice.totals.vatAmount.amount)
        assertEquals(BigDecimal("0.03"), invoice.totals.grossAmount.amount)
    }

    // B1 — the actual invariant the requirement demands: individual positions may be negative
    // (discount/rebate), but validate() succeeds if and only if the aggregated totals stay
    // non-negative, across a random mix of positive and negative position prices.
    @Test
    fun `validate succeeds iff aggregated totals stay non-negative under random positive-negative mixes`() {
        val rnd = Random(91)
        var sawNonNegativeCase = false
        var sawNegativeCase = false
        repeat(200) {
            val invoice = Invoice()
            invoice.edit {
                invoiceNumber = InvoiceNumber("PROP-2026-0001")
                senderProfile = validSender()
                recipient = validRecipient()
            }

            val itemCount = rnd.nextInt(1, 6)
            repeat(itemCount) { index ->
                val qty = BigDecimal((rnd.nextDouble() * 99.9999) + 0.0001).setScale(4, RoundingMode.HALF_UP)
                // signed price: roughly half the draws negative, magnitude up to 1000
                val magnitude = BigDecimal((rnd.nextDouble() * 1000.0)).setScale(4, RoundingMode.HALF_UP)
                val price = if (rnd.nextBoolean()) magnitude else magnitude.negate()
                val rate = BigDecimal(rnd.nextInt(0, 100)).setScale(2, RoundingMode.HALF_UP)

                invoice.addItem(anInvoiceItem(
                    positionId = (index + 1).toString(),
                    description = "item-$index",
                    quantity = qty,
                    unitPriceNet = Money(price),
                    taxRateRef = TaxRate().edit { this.rate = TaxRatePercent(rate) },
                ))
            }

            invoice.recalculateTotals()
            val totalsNonNegative = !invoice.totals.netAmount.isNegative() &&
                !invoice.totals.vatAmount.isNegative() &&
                !invoice.totals.grossAmount.isNegative()

            if (totalsNonNegative) {
                sawNonNegativeCase = true
                invoice.validate() // must not throw
            } else {
                sawNegativeCase = true
                assertThrows(InvoiceValidationException::class.java) { invoice.validate() }
            }
        }
        // Guards against the property degenerating into a vacuous pass (e.g. rnd.nextBoolean()
        // always drawing the same branch) — the fixed seed must actually exercise both sides.
        assertTrue(sawNonNegativeCase, "expected at least one non-negative-totals case")
        assertTrue(sawNegativeCase, "expected at least one negative-totals case")
    }
}
