package de.littletrotter.domain.invoice

import de.littletrotter.shared.Money
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class InvoiceTotalsTest {

    @Test
    fun `recalculate totals sums item amounts`() {
        val invoice = Invoice()

        val item = InvoiceItem()
        item.positionId = "1"
        item.description = "Test position"
        item.quantity = BigDecimal("2")
        item.unitPriceNet = Money.of("10.00")
        item.taxRateRef = TaxRate().apply { rate = TaxRatePercent(BigDecimal("19")) }

        // calculate item amounts and attach
        item.calculateAmounts()
        invoice.addItem(item)

        // totals should reflect item amounts
        invoice.recalculateTotals()

        assertEquals(0, invoice.totals.netAmount.amount.compareTo(BigDecimal("20.00")))
        assertEquals(0, invoice.totals.vatAmount.amount.compareTo(BigDecimal("3.80")))
        assertEquals(0, invoice.totals.grossAmount.amount.compareTo(BigDecimal("23.80")))
    }

    @Test
    fun `RK-1 - recalculate totals includes a TRIP item priced from its expense lines`() {
        val invoice = Invoice()

        val service = InvoiceItem().apply {
            positionId = "1"
            description = "Beratung"
            quantity = BigDecimal.ONE
            unitPriceNet = Money.of("500.00")
            taxRateRef = TaxRate().apply { rate = TaxRatePercent(BigDecimal("19")) }
            calculateAmounts()
        }
        val trip = InvoiceItem().apply {
            positionId = "2"
            category = InvoiceItemCategory.TRIP
            description = "Dienstreise"
            taxRateRef = TaxRate().apply { rate = TaxRatePercent(BigDecimal("19")) }
            tripExpenses.add(
                TripExpense().apply {
                    kind = TripExpenseKind.HOTEL
                    description = "Hotel"
                    quantity = BigDecimal.ONE
                    unitPriceNet = Money.of("100.00")
                    calculateNetAmount()
                },
            )
            calculateAmounts()
        }
        invoice.addItem(service)
        invoice.addItem(trip)

        invoice.recalculateTotals()

        assertEquals(0, invoice.totals.netAmount.amount.compareTo(BigDecimal("600.00")))
        assertEquals(0, invoice.totals.vatAmount.amount.compareTo(BigDecimal("114.00")))
        assertEquals(0, invoice.totals.grossAmount.amount.compareTo(BigDecimal("714.00")))
    }
}
