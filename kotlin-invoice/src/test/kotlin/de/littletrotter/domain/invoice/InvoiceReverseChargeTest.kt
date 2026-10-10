package de.littletrotter.domain.invoice

import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** [Invoice.hasReverseChargeItems] derives purely from item tax categories,
 *  independent of whatever [Invoice.reverseCharge] currently holds (the field these tests set
 *  directly, bypassing [Invoice.recalculateTotals], is exactly the derivation's independence
 *  from a stale/mismatched persisted value that this test class checks). */
class InvoiceReverseChargeTest {

    private fun rule(category: String, code: String = "TEST"): TaxRate = TaxRate().apply {
        this.code = code
        this.category = category
        rate = TaxRatePercent(if (category in setOf("AE", "E", "O")) BigDecimal.ZERO else BigDecimal("19.00"))
        exemptionReasonCode = if (category == "AE") "VATEX-EU-AE" else null
    }

    @Test
    fun `an invoice with an AE item is reverse charge regardless of the stored column`() {
        val item = InvoiceItem().apply { applyTaxRule(rule("AE")) }
        val invoice = Invoice().apply { itemList.asMutableForTest().add(item); reverseCharge = false }

        assertTrue(invoice.hasReverseChargeItems())
    }

    @Test
    fun `an invoice without any AE item is not reverse charge even if the stored column says so`() {
        val item = InvoiceItem().apply { applyTaxRule(rule("S")) }
        val invoice = Invoice().apply { itemList.asMutableForTest().add(item); reverseCharge = true }

        assertFalse(invoice.hasReverseChargeItems())
    }

    @Test
    fun `an invoice with mixed items is reverse charge if at least one item is AE`() {
        val invoice = Invoice().apply {
            itemList.asMutableForTest().add(InvoiceItem().apply { applyTaxRule(rule("S")) })
            itemList.asMutableForTest().add(InvoiceItem().apply { applyTaxRule(rule("AE")) })
        }

        assertTrue(invoice.hasReverseChargeItems())
    }

    @Test
    fun `an invoice with no items is not reverse charge`() {
        assertFalse(Invoice().hasReverseChargeItems())
    }
}
