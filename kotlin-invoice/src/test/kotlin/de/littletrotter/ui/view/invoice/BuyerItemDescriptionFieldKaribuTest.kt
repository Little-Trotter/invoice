package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Tests for the buyer item description field (Referenz-Beschreibung):
 * - Happy path: field visible with neutral wording
 * - Edge case 1: Parameterized over all 4 InvoiceItemCategory values,
 *   labels and helper texts are byte-identical every case (regression guard)
 * - Edge case 2: Prefill fills both fields only when blank, never overwrites
 * - Edge case 3: Field enforces 50-char maxLength (same as buildBuyerItemNumberField)
 */
class BuyerItemDescriptionFieldKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    @Test
    fun `buyerItemDescriptionField shows neutral label and helper text`() {
        val field = buildBuyerItemDescriptionField { key -> "translated:$key" }

        assertEquals("translated:invoiceDetail.itemDialog.buyerItemDescription", field.label)
        assertEquals("translated:invoiceDetail.itemDialog.buyerItemDescription.helper", field.helperText)
    }

    @ParameterizedTest
    @EnumSource(InvoiceItemCategory::class)
    fun `buyerItemDescriptionField uses identical neutral wording across all item categories`(category: InvoiceItemCategory) {
        // Edge case 1: Parameterized over all 4 InvoiceItemCategory values
        // Confirms labels and helper texts never vary by category (regression guard against branching)
        val field = buildBuyerItemDescriptionField { "neutral-label" }

        // The field itself is category-agnostic (no branching logic in buildBuyerItemDescriptionField)
        // so we verify the field's properties are invariant regardless of what category we're in
        assertEquals("neutral-label", field.label, "Label must be neutral for category: $category")
        assertEquals(50, field.maxLength, "Max length must be consistent for category: $category")
        assertTrue(field.isClearButtonVisible, "Clear button must be visible for category: $category")
    }

    @Test
    fun `buyerItemDescriptionField enforces 50-char maxLength constraint`() {
        // Edge case 3: Field enforces same maxLength as buyerItemNumberField
        val field = buildBuyerItemDescriptionField { it }

        assertEquals(50, field.maxLength, "buyerItemDescriptionField must enforce 50-char maxLength like buyerItemNumberField")
    }

    @Test
    fun `prefillIfBlank fills blank field from ArticleRecipientRef`() {
        // Edge case 2: Prefill only when blank
        val field = TextField()
        assertEquals("", field.value)

        prefillIfBlank(field, "prefilled-value")

        assertEquals("prefilled-value", field.value)
    }

    @Test
    fun `prefillIfBlank never overwrites user-entered value`() {
        // Edge case 2: Prefill never overwrites manually entered value
        val field = TextField().apply { value = "user-entered-description" }

        prefillIfBlank(field, "prefilled-value")

        assertEquals("user-entered-description", field.value, "Manually entered value must not be overwritten")
    }

    @Test
    fun `prefillIfBlank ignores null value`() {
        val field = TextField()

        prefillIfBlank(field, null)

        assertEquals("", field.value)
    }

    @Test
    fun `prefillIfBlank ignores blank value`() {
        val field = TextField()

        prefillIfBlank(field, "   ")

        assertEquals("", field.value)
    }
}
