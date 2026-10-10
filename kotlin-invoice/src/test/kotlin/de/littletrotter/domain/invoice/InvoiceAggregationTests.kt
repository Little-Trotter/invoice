package de.littletrotter.domain.invoice

import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class InvoiceAggregationTests {

    private fun taxRate(rate: String): TaxRate = TaxRate().apply { this.rate = TaxRatePercent(BigDecimal(rate)) }

    @Test
    fun `single item 19 percent computes expected totals`() {
        val invoice = Invoice()
        val item = InvoiceItem().apply {
            positionId = "1"
            description = "Service"
            quantity = BigDecimal.ONE
            unitPriceNet = Money.of(BigDecimal("100.00"))
            taxRateRef = taxRate("19.00")
        }
        invoice.addItem(item)
        invoice.recalculateTotals()

        // invoice.rounding normalizes to 2 decimals
        assertEquals(0, invoice.totals.netAmount.amount.compareTo(BigDecimal("100.00")))
        assertEquals(0, invoice.totals.vatAmount.amount.compareTo(BigDecimal("19.00")))
        assertEquals(0, invoice.totals.grossAmount.amount.compareTo(BigDecimal("119.00")))
    }

    @Test
    fun `multiple items with different rates remain algebraically consistent`() {
        val invoice = Invoice()

        val item1 = InvoiceItem().apply {
            positionId = "1"
            description = "Small"
            quantity = BigDecimal("1")
            unitPriceNet = Money.of(BigDecimal("1.00"))
            taxRateRef = taxRate("7.50")
        }

        val item2 = InvoiceItem().apply {
            positionId = "2"
            description = "Large"
            quantity = BigDecimal("3")
            unitPriceNet = Money.of(BigDecimal("19.99"))
            taxRateRef = taxRate("19.00")
        }

        invoice.addItem(item1)
        invoice.addItem(item2)
        invoice.recalculateTotals()

        // Check algebraic consistency: net + vat == gross
        val sum = invoice.totals.netAmount.amount.add(invoice.totals.vatAmount.amount)
        assertEquals(0, sum.compareTo(invoice.totals.grossAmount.amount))

        // And totals are non-negative and scaled to 2 decimals after recalculateTotals
        assertEquals(2, invoice.totals.netAmount.amount.scale())
        assertEquals(2, invoice.totals.vatAmount.amount.scale())
        assertEquals(2, invoice.totals.grossAmount.amount.scale())
    }

    // ── improve/IMP-5 — addItem only fills in a sortOrder the caller left unset ───────────

    private fun freeTextItem(): InvoiceItem = InvoiceItem().apply {
        description = "Position"
        quantity = BigDecimal.ONE
        unitPriceNet = Money.of(BigDecimal("10.00"))
        taxRateRef = taxRate("19.00")
    }

    @Test
    fun `the first item on an empty invoice gets sortOrder 1`() {
        val invoice = Invoice()
        invoice.addItem(freeTextItem())

        assertEquals(1, invoice.itemList.single().sortOrder)
    }

    @Test
    fun `an unset sortOrder continues one past the highest existing value`() {
        val invoice = Invoice()
        invoice.addItem(freeTextItem())
        invoice.addItem(freeTextItem())
        invoice.addItem(freeTextItem())

        assertEquals(listOf(1, 2, 3), invoice.itemList.map { it.sortOrder })
    }

    @Test
    fun `a caller-supplied sortOrder is kept, not overwritten`() {
        val invoice = Invoice()
        invoice.addItem(freeTextItem().apply { sortOrder = 5 })

        assertEquals(5, invoice.itemList.single().sortOrder)
    }

    @Test
    fun `RK-1 - addItem numbers a TRIP position the same way as any other category`() {
        // README border case 8 — position numbers stay flat ("3"), regardless of category;
        // addItem itself never looks at category at all, this pins that it stays that way.
        val invoice = Invoice()
        invoice.addItem(freeTextItem())
        invoice.addItem(freeTextItem())
        invoice.addItem(
            InvoiceItem().apply {
                category = de.littletrotter.shared.catalogue.InvoiceItemCategory.TRIP
                description = "Dienstreise"
                taxRateRef = taxRate("19.00")
            },
        )

        val trip = invoice.itemList.last()
        assertEquals("3", trip.positionId)
        assertEquals(3, trip.sortOrder)
    }

    @Test
    fun `an out-of-order existing sortOrder does not confuse the running maximum`() {
        // Every other test happens to add items whose sortOrder keeps increasing, so the running
        // maximum inside maxOfOrNull is updated on every element. Set an existing list up with a
        // later, smaller value so the scan also exercises the "does not exceed the current
        // maximum" branch.
        val invoice = Invoice()
        invoice.addItem(freeTextItem().apply { sortOrder = 5 })
        invoice.addItem(freeTextItem().apply { sortOrder = 2 })

        invoice.addItem(freeTextItem())

        assertEquals(6, invoice.itemList.last().sortOrder)
    }

    @Test
    fun `after deleting the middle position, the next auto sortOrder still counts from the highest survivor`() {
        // improve/IMP-5's second bug: items.size alone would recompute 2 here (2 survivors),
        // colliding with the still-existing sortOrder 3 (the third original position).
        val invoice = Invoice()
        val first = freeTextItem()
        val second = freeTextItem()
        val third = freeTextItem()
        invoice.addItem(first)
        invoice.addItem(second)
        invoice.addItem(third)
        invoice.removeItem(second)

        invoice.addItem(freeTextItem())

        assertEquals(listOf(1, 3, 4), invoice.itemList.map { it.sortOrder })
    }
}
