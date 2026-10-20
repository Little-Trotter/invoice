package de.littletrotter.xpdf.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** A row built without data renders as blank cells, never as the text "null". */
class RenderContextDefaultsTest {

    @Test
    fun `a tax breakdown row without data has blank amounts and no category`() {
        val row = TaxBreakdownRowContext()

        assertEquals("", row.rate)
        assertNull(row.category)
        assertEquals("", row.net)
        assertEquals("", row.vat)
    }

    @Test
    fun `a trip expense row without data has no label, quantity or amount`() {
        val expense = TripExpenseRenderContext()

        assertNull(expense.kindLabelKey)
        assertNull(expense.description)
        assertNull(expense.quantity)
        assertEquals("", expense.unitLabel)
        assertNull(expense.netAmount)
    }

    @Test
    fun `a given value replaces only its own default`() {
        val row = TaxBreakdownRowContext(rate = "19,00")

        assertEquals("19,00", row.rate)
        assertEquals("", row.vat)
    }
}
