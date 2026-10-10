package de.littletrotter.ui.view.invoice

import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Tests for [InvoiceDetailView.calculateAmounts] - the UI helper that computes line-item
 * net/vat/gross amounts with proper rounding of the unit price before multiplication.
 *
 * This test class ensures that:
 * - Unit prices are rounded to 2 decimals BEFORE multiplying with quantity
 * - Results match [InvoiceItem.calculateAmountsSnapshot] for the same inputs
 * - Edge cases (zero tax, zero quantity, very small prices) are handled correctly
 */
class InvoiceDetailViewCalculateAmountsTest {

    @Test
    fun `calculateAmounts rounds unit price to 2 decimals before multiplying`() {
        // 0.3333 × 3 = 0.9999 → rounds to 0.99 net, not 1.00
        // VAT: 0.99 × 19% = 0.1881 → rounds to 0.19
        // Gross: 0.99 + 0.19 = 1.18 (not 1.19 which would result from unrounded calculation)
        val (net, vat, gross) = InvoiceDetailView.calculateAmounts(
            BigDecimal("3"),
            BigDecimal("0.3333"),
            BigDecimal("19")
        )

        assertEquals(BigDecimal("0.99"), net)
        assertEquals(BigDecimal("0.19"), vat)
        assertEquals(BigDecimal("1.18"), gross)
    }

    @Test
    fun `calculateAmounts matches InvoiceItem calculateAmountsSnapshot for same inputs`() {
        // Test that UI helper produces the same result as domain logic
        val qty = BigDecimal("2.5")
        val price = BigDecimal("10.333") // Should round to 10.33
        val taxRate = BigDecimal("7")

        val (uiNet, uiVat, uiGross) = InvoiceDetailView.calculateAmounts(qty, price, taxRate)

        val item = InvoiceItem().apply {
            quantity = qty
            unitPriceNet = Money.of(price.toString())
            taxRateRef = TaxRate().apply { rate = TaxRatePercent(taxRate) }
        }
        item.calculateAmounts()

        assertEquals(uiNet, item.netAmount.amount)
        assertEquals(uiVat, item.vatAmount.amount)
        assertEquals(uiGross, item.grossAmount.amount)
    }

    @Test
    fun `calculateAmounts with zero tax rate produces net equals gross`() {
        val (net, vat, gross) = InvoiceDetailView.calculateAmounts(
            BigDecimal("10"),
            BigDecimal("5.555"), // Rounds to 5.56
            BigDecimal("0")
        )

        assertEquals(BigDecimal("55.60"), net)
        assertEquals(BigDecimal("0.00"), vat)
        assertEquals(BigDecimal("55.60"), gross)
    }

    @Test
    fun `calculateAmounts with zero quantity returns all zeros`() {
        val (net, vat, gross) = InvoiceDetailView.calculateAmounts(
            BigDecimal("0"),
            BigDecimal("99.99"),
            BigDecimal("19")
        )

        assertEquals(BigDecimal("0.00"), net)
        assertEquals(BigDecimal("0.00"), vat)
        assertEquals(BigDecimal("0.00"), gross)
    }

    @Test
    fun `calculateAmounts with very small price rounds to zero`() {
        // 0.001 × 1 = 0.001 → rounds to 0.00
        val (net, vat, gross) = InvoiceDetailView.calculateAmounts(
            BigDecimal("1"),
            BigDecimal("0.001"),
            BigDecimal("19")
        )

        assertEquals(BigDecimal("0.00"), net)
        assertEquals(BigDecimal("0.00"), vat)
        assertEquals(BigDecimal("0.00"), gross)
    }

    @Test
    fun `calculateAmounts handles exact cent values without additional rounding`() {
        val (net, vat, gross) = InvoiceDetailView.calculateAmounts(
            BigDecimal("1"),
            BigDecimal("10.00"), // Already at 2 decimals
            BigDecimal("19")
        )

        assertEquals(BigDecimal("10.00"), net)
        assertEquals(BigDecimal("1.90"), vat)
        assertEquals(BigDecimal("11.90"), gross)
    }

    @Test
    fun `calculateAmounts handles large quantities with proper rounding`() {
        // 100 × 0.333: price rounds to 0.33 first, then 100 × 0.33 = 33.00
        val (net, vat, gross) = InvoiceDetailView.calculateAmounts(
            BigDecimal("100"),
            BigDecimal("0.333"),
            BigDecimal("19")
        )

        assertEquals(BigDecimal("33.00"), net)
        assertEquals(BigDecimal("6.27"), vat) // 33.00 × 0.19 = 6.27
        assertEquals(BigDecimal("39.27"), gross)
    }
}
