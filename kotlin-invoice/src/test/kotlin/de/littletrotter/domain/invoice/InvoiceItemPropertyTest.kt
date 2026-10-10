package de.littletrotter.domain.invoice

import de.littletrotter.shared.Money
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.random.Random

class InvoiceItemPropertyTest {

    @Test
    fun `calculateAmounts preserves gross equals net plus vat for many random inputs`() {
        val rnd = Random(42)
        repeat(1000) {
            val item = InvoiceItem()
            // quantity between 0.0001 and 1000 with up to 4 decimals
            val qty = BigDecimal((rnd.nextDouble() * 999.9999) + 0.0001).setScale(4, RoundingMode.HALF_UP)
            val price = BigDecimal((rnd.nextDouble() * 10000.0)).setScale(4, RoundingMode.HALF_UP)
            val rate = BigDecimal(rnd.nextInt(0, 100)).setScale(2, RoundingMode.HALF_UP)
            item.quantity = qty
            item.unitPriceNet = Money(price)
            item.taxRateRef = TaxRate().apply { this.rate = TaxRatePercent(rate) }
            item.positionId = "p"
            item.description = "d"

            item.calculateAmounts()

            // Task A: calculateAmountsSnapshot() rounds the unit price to currency scale BEFORE
            // multiplying by quantity, so the oracle here must do the same instead of rounding
            // only the final product (qty.multiply(price)) — the two are not equivalent whenever
            // `price` carries sub-cent precision, by design (see InvoiceCalculationEdgeCasesTest
            // `high precision unit price`).
            val effectivePrice = price.setScale(2, RoundingMode.HALF_UP)
            val expectedNet = qty.multiply(effectivePrice).setScale(2, RoundingMode.HALF_UP)
            assertEquals(0, item.netAmount.amount.compareTo(expectedNet), "net mismatch for qty=$qty price=$price")

            val expectedVat = expectedNet.multiply(rate).divide(BigDecimal("100"), 2, RoundingMode.HALF_UP)
                assertEquals(0, item.vatAmount.amount.compareTo(expectedVat), "vat amount mismatch for expected=$expectedVat actual=${item.vatAmount.amount}")

            val grossRaw = expectedNet.add(expectedVat)
            val expectedGross = grossRaw.setScale(2, RoundingMode.HALF_UP)
            assertEquals(0, item.grossAmount.amount.compareTo(expectedGross), "gross != expected gross for rate=$rate expected=$expectedGross actual=${item.grossAmount.amount}")

            assertEquals(2, item.netAmount.amount.scale(), "net scale unexpected")
            assertEquals(2, item.vatAmount.amount.scale(), "vat scale unexpected")
            assertEquals(2, item.grossAmount.amount.scale(), "gross scale unexpected")
        }
    }

    @Test
    fun `calculateAmounts rounds unit price to currency scale before multiplying`() {
        val item = InvoiceItem().apply {
            quantity = BigDecimal("3")
            // 0.335 rounds HALF_UP to 0.34 before the multiply (task A) -> 3 x 0.34 = 1.02,
            // not the pre-task-A result of rounding 3 x 0.335 = 1.005 up to 1.01.
            unitPriceNet = Money(BigDecimal("0.335"))
            taxRateRef = TaxRate().apply { rate = TaxRatePercent(BigDecimal("19")) }
            positionId = "p"
            description = "d"
        }

        item.calculateAmounts()

        assertEquals(BigDecimal("1.02"), item.netAmount.amount)
        assertEquals(BigDecimal("0.19"), item.vatAmount.amount)
        assertEquals(BigDecimal("1.21"), item.grossAmount.amount)
    }
}
