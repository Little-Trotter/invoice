package de.littletrotter.application.invoice

import de.littletrotter.shared.tax.VatCategory
import de.littletrotter.ui.view.quote.quoteItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class QuoteReverseChargeDecisionTest {

    private fun item(position: Int, category: VatCategory) = quoteItem(position).copy(vatCategory = category)

    @Test
    fun `all positions reverse charge is a yes`() {
        assertEquals(true, QuoteReverseChargeDecision.derive(listOf(item(1, VatCategory.REVERSE_CHARGE), item(2, VatCategory.REVERSE_CHARGE))))
    }

    @Test
    fun `no reverse charge position is a no`() {
        assertEquals(false, QuoteReverseChargeDecision.derive(listOf(item(1, VatCategory.STANDARD), item(2, VatCategory.STANDARD))))
    }

    @Test
    fun `no positions at all is a no`() {
        assertEquals(false, QuoteReverseChargeDecision.derive(emptyList()))
    }

    @Test
    fun `mixed categories have no decision`() {
        assertNull(QuoteReverseChargeDecision.derive(listOf(item(1, VatCategory.REVERSE_CHARGE), item(2, VatCategory.STANDARD))))
    }
}
