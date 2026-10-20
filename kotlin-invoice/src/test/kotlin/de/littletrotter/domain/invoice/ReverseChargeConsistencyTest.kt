package de.littletrotter.domain.invoice

import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.fixture.edit
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceItem

/** [Invoice.validateReverseChargeItemConsistency]: positions must agree with the
 *  invoice's own leading [Invoice.reverseCharge] decision. Companion to
 *  [de.littletrotter.domain.compliance.TaxCategoryConsistencyTest]'s
 *  `assertReverseChargeRecipientEligibility` coverage — this class only tests decision-vs-items,
 *  never the recipient side (that needs the resolver, out of this package's dependency direction). */
class ReverseChargeConsistencyTest {

    private fun item(positionId: String, category: String?) = anInvoiceItem(
        positionId = positionId,
        description = "Position",
        quantity = BigDecimal.ONE,
        unitPriceNet = de.littletrotter.shared.Money(BigDecimal("100")),
        taxRateRef = null,
    ).apply {
        if (category != null) {
            applyTaxRule(
                TaxRate().edit {
                    code = "TEST_$category"
                    this.category = category
                    rate = TaxRatePercent(if (category == "AE") BigDecimal.ZERO else BigDecimal("19.00"))
                    if (category == "AE") exemptionReasonCode = "VATEX-EU-AE"
                },
            )
        }
    }

    @Test
    fun `decision OFF with only standard-rate items passes`() {
        val invoice = anInvoice(reverseCharge = false)
        invoice.addItem(item("1", "S"))
        invoice.addItem(item("2", "S"))
        assertDoesNotThrow { invoice.validateReverseChargeItemConsistency() }
    }

    @Test
    fun `decision ON with only AE items passes`() {
        val invoice = anInvoice(reverseCharge = true)
        invoice.addItem(item("1", "AE"))
        invoice.addItem(item("2", "AE"))
        assertDoesNotThrow { invoice.validateReverseChargeItemConsistency() }
    }

    @Test
    fun `items without a tax snapshot never participate, regardless of the decision`() {
        val onInvoice = anInvoice(reverseCharge = true)
        onInvoice.addItem(item("1", null))
        assertDoesNotThrow { onInvoice.validateReverseChargeItemConsistency() }

        val offInvoice = anInvoice(reverseCharge = false)
        offInvoice.addItem(item("1", null))
        assertDoesNotThrow { offInvoice.validateReverseChargeItemConsistency() }
    }

    @Test
    fun `decision ON blocks on a non-AE item and names its position`() {
        val invoice = anInvoice(reverseCharge = true)
        invoice.addItem(item("1", "AE"))
        invoice.addItem(item("2", "S"))

        val ex = assertThrows(InvoiceValidationException::class.java) { invoice.validateReverseChargeItemConsistency() }
        assertEquals(InvoiceValidationError.REVERSE_CHARGE_ITEM_NOT_AE, ex.error)
        assertEquals(listOf("2"), ex.args)
    }

    @Test
    fun `decision OFF blocks on an AE item and names its position`() {
        val invoice = anInvoice(reverseCharge = false)
        invoice.addItem(item("1", "S"))
        invoice.addItem(item("2", "AE"))

        val ex = assertThrows(InvoiceValidationException::class.java) { invoice.validateReverseChargeItemConsistency() }
        assertEquals(InvoiceValidationError.REVERSE_CHARGE_ITEM_UNEXPECTED_AE, ex.error)
        assertEquals(listOf("2"), ex.args)
    }

    @Test
    fun `decision ON names every offending position, not just the first`() {
        val invoice = anInvoice(reverseCharge = true)
        invoice.addItem(item("1", "AE"))
        invoice.addItem(item("2", "S"))
        invoice.addItem(item("3", "S"))

        val ex = assertThrows(InvoiceValidationException::class.java) { invoice.validateReverseChargeItemConsistency() }
        assertEquals(listOf("2, 3"), ex.args)
    }
}
