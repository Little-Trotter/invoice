package de.littletrotter.domain.invoice

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.fixture.edit
import de.littletrotter.shared.Money
import de.littletrotter.domain.exception.InvoiceValidationError
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import de.littletrotter.fixture.anInvoiceItem

class InvoiceItemValidationTests {

    private fun taxRate(rate: String): TaxRate = TaxRate().edit { this.rate = TaxRatePercent(BigDecimal(rate)) }

    @Test
    fun `missing positionId or description throws`() {
        val it = anInvoiceItem(
            positionId = "",
            description = "",
            quantity = BigDecimal.ONE,
            unitPriceNet = Money.of(BigDecimal("10.00")),
            taxRateRef = taxRate("19.00"),
        ).apply {
            calculateAmounts()
        }
        assertThrows(InvoiceValidationException::class.java) { it.validate() }
    }

    @Test
    fun `a blank description alone throws, even with a positionId already set`() {
        // The other "missing positionId or description" test leaves both blank, so positionId's
        // check (which runs first) always wins the throw and description's own branch never runs.
        val it = anInvoiceItem(
            positionId = "1",
            description = "",
            quantity = BigDecimal.ONE,
            unitPriceNet = Money.of(BigDecimal("10.00")),
            taxRateRef = taxRate("19.00"),
        ).apply {
            calculateAmounts()
        }
        assertThrows(InvoiceValidationException::class.java) { it.validate() }
    }

    /*
     * TaxRatePercent now rejects an out-of-range value at construction. Both paths
     * effectiveTaxRate() can read from (taxRateSnapshot and, since , taxRateRef.rate on the
     * live TaxRate reference too) are TaxRatePercent-backed, so an InvoiceItem can never end up
     * with an out-of-range effective rate any more — InvoiceItem.validate()'s former deferred
     * range check was dead code (removed). These two tests now pin the fail-fast guarantee at the
     * construction site InvoiceItem's own tax-rate wiring goes through, `taxRate(...)`.
     */
    @Test
    fun `constructing a negative tax rate throws immediately`() {
        assertThrows(InvalidParameterException::class.java) { taxRate("-1") }
    }

    @Test
    fun `constructing a tax rate above 100pct throws immediately`() {
        assertThrows(InvalidParameterException::class.java) { taxRate("101") }
    }

    @Test
    fun `negative quantity throws`() {
        val it = anInvoiceItem(
            positionId = "1",
            description = "x",
            quantity = BigDecimal("-1"),
            unitPriceNet = Money.of(BigDecimal("10.00")),
            taxRateRef = taxRate("19.00"),
        ).apply {
            calculateAmounts()
        }
        assertThrows(InvoiceValidationException::class.java) { it.validate() }
    }

    // B1 — a discount/rebate position carries a negative unitPriceNet on purpose; only the
    // aggregated invoice totals must stay non-negative (InvoiceValidationTests / InvoiceTotalsPropertyTest).
    @Test
    fun `negative unitPriceNet does not throw`() {
        val it = anInvoiceItem(
            positionId = "1",
            description = "Rabatt 10 %",
            quantity = BigDecimal.ONE,
            unitPriceNet = Money.of(BigDecimal("-10.00")),
            taxRateRef = taxRate("19.00"),
        ).apply {
            calculateAmounts()
        }
        assertDoesNotThrow { it.validate() }
        assert(it.netAmount.isNegative())
        assert(it.grossAmount.isNegative())
    }

    @Test
    fun `negative net, vat and gross amounts do not throw`() {
        // negative amounts exactly as calculateAmounts() derives them for a negative price
        val it = anInvoiceItem(
            positionId = "1",
            description = "Rabatt",
            quantity = BigDecimal.ONE,
            unitPriceNet = Money.of(BigDecimal("-1.00")),
            taxRateRef = taxRate("19.00"),
        ).also { item -> item.calculateAmounts() }
        assertTrue(it.netAmount.isNegative() && it.vatAmount.isNegative() && it.grossAmount.isNegative())
        assertDoesNotThrow { it.validate() }
    }

    private fun itemWith(taxRateRef: TaxRate?, taxCategory: String?) = anInvoiceItem(
        positionId = "1",
        description = "Consulting",
        quantity = BigDecimal.ONE,
        unitPriceNet = Money.of(BigDecimal("10.00")),
        taxRateRef = taxRateRef,
        taxCategory = taxCategory,
    )

    @Test
    fun `a position without a tax rate reference is rejected`() {
        val item = itemWith(taxRateRef = null, taxCategory = "S")

        val ex = assertThrows(InvoiceValidationException::class.java) { item.validate() }

        assertEquals(InvoiceValidationError.ITEM_TAX_RATE_REFERENCE_MISSING, ex.error)
    }

    @Test
    fun `a position with a tax rate but without a tax category is rejected`() {
        val item = itemWith(taxRateRef = taxRate("19.00"), taxCategory = null)

        val ex = assertThrows(InvoiceValidationException::class.java) { item.validate() }

        assertEquals(InvoiceValidationError.ITEM_TAX_RATE_REFERENCE_MISSING, ex.error)
    }
}
