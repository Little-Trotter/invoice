package de.littletrotter.ui.component

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import de.littletrotter.shared.Currency
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Covers the `emphasized` branch in
 * [AmountPreview.amountLine] (net/vat lines are plain, the gross line carries the "emphasized"
 * class) was unexercised.
 */
class AmountPreviewTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun rows(preview: AmountPreview): List<HorizontalLayout> =
        preview.children.toList().filterIsInstance<HorizontalLayout>()

    @Test
    fun `only the gross (last) row carries the emphasized class`() {
        val preview = AmountPreview(Currency.EUR)
        val rowList = rows(preview)
        assertFalse(rowList[0].hasClassName("emphasized"), "net row must not be emphasized")
        assertFalse(rowList[1].hasClassName("emphasized"), "vat row must not be emphasized")
        assertTrue(rowList[2].hasClassName("emphasized"), "gross row must be emphasized")
    }

    @Test
    fun `update renders the tax rate percent alongside the VAT label`() {
        val preview = AmountPreview(Currency.EUR)
        preview.update(BigDecimal("100.00"), BigDecimal("19.00"), BigDecimal("119.00"), BigDecimal("19.00"))

        val vatLabel = (rows(preview)[1].children.toList()[0] as Span).text
        assertTrue(vatLabel.contains("19"), "the VAT line label must include the tax rate percent: $vatLabel")
    }

    @Test
    fun `update formats every amount in the given currency`() {
        val preview = AmountPreview(Currency.EUR)
        preview.update(BigDecimal("100.00"), BigDecimal("19.00"), BigDecimal("119.00"), BigDecimal("19.00"))

        val grossAmount = (rows(preview)[2].children.toList()[1] as Span).text
        assertTrue(grossAmount.contains("119"), "gross amount text must reflect the passed value: $grossAmount")
    }
}
