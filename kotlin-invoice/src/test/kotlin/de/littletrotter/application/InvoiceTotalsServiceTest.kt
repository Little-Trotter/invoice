package de.littletrotter.application

import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class InvoiceTotalsServiceTest {

    @Test
    fun `empty invoice results in zero totals`() {
        val invoice = Invoice()

        invoice.recalculateTotals()

        assertEquals(BigDecimal("0.00"), invoice.totals.netAmount.amount)
        assertEquals(BigDecimal("0.00"), invoice.totals.vatAmount.amount)
        assertEquals(BigDecimal("0.00"), invoice.totals.grossAmount.amount)
    }

    @Test
    fun `single item net vat and gross are computed correctly`() {
        val invoice = invoiceWith(item(quantity = "10", unitPrice = "100.00", taxRate = "19"))

        invoice.recalculateTotals()

        val item = invoice.itemList.first()
        assertEquals(bd("1000.00"), item.netAmount.amount)
        assertEquals(bd("190.00"), item.vatAmount.amount)
        assertEquals(bd("1190.00"), item.grossAmount.amount)

        assertEquals(bd("1000.00"), invoice.totals.netAmount.amount)
        assertEquals(bd("190.00"), invoice.totals.vatAmount.amount)
        assertEquals(bd("1190.00"), invoice.totals.grossAmount.amount)
    }

    @Test
    fun `multiple items are aggregated`() {
        val invoice = invoiceWith(
            item(quantity = "8", unitPrice = "100.00", taxRate = "19"),
            item(quantity = "2", unitPrice = "50.00",  taxRate = "7"),
        )

        invoice.recalculateTotals()

        assertEquals(bd("900.00"), invoice.totals.netAmount.amount)
        assertEquals(bd("159.00"), invoice.totals.vatAmount.amount)  // 8*100*0.19 + 2*50*0.07 = 152 + 7 = 159
        assertEquals(bd("1059.00"), invoice.totals.grossAmount.amount)
    }

    @Test
    fun `zero tax rate results in zero vat`() {
        val invoice = invoiceWith(item(quantity = "1", unitPrice = "500.00", taxRate = "0"))

        invoice.recalculateTotals()

        assertEquals(bd("500.00"), invoice.totals.netAmount.amount)
        assertEquals(bd("0.00"), invoice.totals.vatAmount.amount)
        assertEquals(bd("500.00"), invoice.totals.grossAmount.amount)
    }

    @Test
    fun `fractional quantity is rounded to currency scale`() {
        val invoice = invoiceWith(item(quantity = "1.5", unitPrice = "66.67", taxRate = "19"))

        invoice.recalculateTotals()

        val item = invoice.itemList.first()
        // 1.5 * 66.67 = 100.005 → rounded HALF_UP to 2 decimal places = 100.01
        assertEquals(bd("100.01"), item.netAmount.amount)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun bd(value: String) = BigDecimal(value)

    private fun item(quantity: String, unitPrice: String, taxRate: String): InvoiceItem =
        InvoiceItem().apply {
            this.quantity = bd(quantity)
            this.unitPriceNet = de.littletrotter.shared.Money(bd(unitPrice))
            this.taxRateRef = TaxRate().apply { rate = TaxRatePercent(bd(taxRate)) }
        }

    private fun invoiceWith(vararg items: InvoiceItem): Invoice =
        Invoice().also { inv -> items.forEach { inv.addItem(it) } }
}
