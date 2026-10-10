package de.littletrotter.domain.invoice

import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.shared.Money
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Item-level amounts are always rounded HALF_UP to the 2-decimal currency scale — see
 * [InvoiceItem.calculateAmountsSnapshot] and commit 8ccfd894 ("Enforce line-item currency
 * rounding in invoice core"), which replaced an earlier scale-4-with-conditional-rounding design.
 */
class InvoiceItemCalculateAmountsTest {

    @Test
    fun `calculate amounts chooses currency scale`() {
        val item = InvoiceItem()
        item.positionId = "1"
        item.description = "test"
        item.quantity = BigDecimal("1")
        item.unitPriceNet = Money.of("100.00")
        item.taxRateRef = TaxRate().apply { rate = TaxRatePercent(BigDecimal("19")) }
        item.calculateAmounts()

        assertEquals(0, item.netAmount.amount.compareTo(BigDecimal("100.00")))
        assertEquals(2, item.vatAmount.amount.scale())

        // gross should be sum of net and vat
        val gross = item.netAmount.amount.add(item.vatAmount.amount)
        assertEquals(0, item.grossAmount.amount.compareTo(gross))
    }

    @Test
    fun `calculateAmounts rounds VAT to 2 decimals when result is exact`() {
        // 100.00 * 19% = 19.00 → exact at 2 decimals
        val item = InvoiceItem().apply {
            quantity = BigDecimal("1")
            unitPriceNet = Money.of("100.00")
            taxRateRef = TaxRate().apply { rate = TaxRatePercent(BigDecimal("19")) }
        }
        item.calculateAmounts()

        assertEquals(2, item.vatAmount.amount.scale())
        assertEquals(2, item.grossAmount.amount.scale())
        assertEquals(0, item.vatAmount.amount.compareTo(BigDecimal("19.00")))
    }

    @Test
    fun `calculateAmounts rounds VAT to 2 decimals for sub-cent fractions`() {
        // 1.23 * 19% = 0.2337 → not exact at 2 decimals → rounded to 0.23
        val item = InvoiceItem().apply {
            quantity = BigDecimal("1")
            unitPriceNet = Money.of("1.23")
            taxRateRef = TaxRate().apply { rate = TaxRatePercent(BigDecimal("19")) }
        }
        item.calculateAmounts()

        assertEquals(2, item.vatAmount.amount.scale())
        assertEquals(2, item.grossAmount.amount.scale())
        assertEquals(0, item.vatAmount.amount.compareTo(BigDecimal("0.23")))
    }

    @Test
    fun `calculateAmounts gross scale is always 2`() {
        // 10.00 * 10% = 1.00 → exact
        val item = InvoiceItem().apply {
            quantity = BigDecimal("1")
            unitPriceNet = Money.of("10.00")
            taxRateRef = TaxRate().apply { rate = TaxRatePercent(BigDecimal("10")) }
        }
        item.calculateAmounts()

        assertEquals(2, item.vatAmount.amount.scale())
        assertEquals(2, item.grossAmount.amount.scale())
    }

    @Test
    fun `calculateAmounts gross scale is 2 for non-exact VAT too`() {
        // 1.11 * 7% = 0.0777 → not exact
        val item = InvoiceItem().apply {
            quantity = BigDecimal("1")
            unitPriceNet = Money.of("1.11")
            taxRateRef = TaxRate().apply { rate = TaxRatePercent(BigDecimal("7")) }
        }
        item.calculateAmounts()

        assertEquals(2, item.vatAmount.amount.scale())
        assertEquals(2, item.grossAmount.amount.scale())
    }

    // ── Branch: effectiveTaxRate throws when taxRateRef is null ─────────────────

    @Test
    fun `effectiveTaxRate throws when taxRateRef is null`() {
        val item = InvoiceItem().apply {
            quantity = BigDecimal("1")
            unitPriceNet = Money.of("10.00")
            taxRateRef = null
        }
        assertThrows(InvoiceValidationException::class.java) { item.effectiveTaxRate() }
    }

    @Test
    fun `calculateAmounts throws when taxRateRef is null`() {
        val item = InvoiceItem().apply {
            quantity = BigDecimal("1")
            unitPriceNet = Money.of("10.00")
            taxRateRef = null
        }
        assertThrows(InvoiceValidationException::class.java) { item.calculateAmounts() }
    }

    // ── Net amount precision: always the 2-decimal currency scale ───────────────

    @Test
    fun `calculateAmounts net amount is always stored at currency scale`() {
        val item = InvoiceItem().apply {
            quantity = BigDecimal("3")
            unitPriceNet = Money.of("0.3333")
            taxRateRef = TaxRate().apply { rate = TaxRatePercent(BigDecimal("19")) }
        }
        item.calculateAmounts()

        assertEquals(2, item.netAmount.amount.scale())
    }

    // ── recalculateTotals with zero items ─────────────────────────────────────────

    @Test
    fun `recalculateTotals with no items results in zero totals`() {
        val invoice = Invoice()
        invoice.recalculateTotals()

        assertEquals(0, invoice.totals.netAmount.amount.compareTo(BigDecimal.ZERO))
        assertEquals(0, invoice.totals.vatAmount.amount.compareTo(BigDecimal.ZERO))
        assertEquals(0, invoice.totals.grossAmount.amount.compareTo(BigDecimal.ZERO))
    }

    // ── recalculateTotals aggregates all items and rounds to 2 decimals ───────────

    @Test
    fun `recalculateTotals invoice totals are rounded to 2 decimal places`() {
        val invoice = Invoice()
        repeat(3) {
            invoice.addItem(InvoiceItem().apply {
                quantity = BigDecimal("1")
                unitPriceNet = Money.of("10.00")
                taxRateRef = TaxRate().apply { rate = TaxRatePercent(BigDecimal("19")) }
            })
        }
        invoice.recalculateTotals()

        assertEquals(2, invoice.totals.netAmount.amount.scale())
        assertEquals(2, invoice.totals.vatAmount.amount.scale())
        assertEquals(2, invoice.totals.grossAmount.amount.scale())
    }
}
