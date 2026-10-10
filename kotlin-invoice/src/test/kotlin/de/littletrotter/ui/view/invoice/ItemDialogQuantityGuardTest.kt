package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.textfield.NumberField
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Euro² prevention (see `ItemDialogQuantityGuard.kt`'s own KDoc) — the UI-side guard on top of the
 * domain rule `LT-UNIT-02`. Covers [wireEuroQuantityGuard] (live changes: manual unitBox picks and
 * an article selection listener's programmatic set both go through the same ComboBox
 * value-change event, so a plain assignment below stands in for both) and
 * [applyEuroQuantityGuardState] (the initial, non-rewriting bind for a dialog opened on an
 * existing item — including the pre-fix legacy case: EURO with a stored quantity != 1).
 */
class ItemDialogQuantityGuardTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun unitBox() = ComboBox<InvoiceItemUnit>().apply { setItems(*InvoiceItemUnit.entries.toTypedArray()) }

    // --- wireEuroQuantityGuard: live changes -------------------------------------------------

    @Test
    fun `selecting EURO locks quantity at 1 and shows the helper text`() {
        val qtyField = NumberField().apply { value = 5.0 }
        val unit = unitBox().apply { value = InvoiceItemUnit.PIECE }
        wireEuroQuantityGuard(qtyField, unit) { key -> key }

        unit.value = InvoiceItemUnit.EURO

        assertEquals(1.0, qtyField.value)
        assertTrue(qtyField.isReadOnly, "quantity must be locked while unit is EURO")
        assertEquals(EURO_QUANTITY_HELPER_KEY, qtyField.helperText)
        assertFalse(qtyField.isInvalid)
    }

    @Test
    fun `switching away from EURO unlocks the field and clears the helper, keeping the value`() {
        val qtyField = NumberField().apply { value = 5.0 }
        val unit = unitBox().apply { value = InvoiceItemUnit.PIECE }
        wireEuroQuantityGuard(qtyField, unit) { key -> key }
        unit.value = InvoiceItemUnit.EURO
        assertTrue(qtyField.isReadOnly)

        unit.value = InvoiceItemUnit.PIECE

        assertFalse(qtyField.isReadOnly)
        assertNull(qtyField.helperText)
        // 1.0 stays until the user edits it — switching away never resets the value further.
        assertEquals(1.0, qtyField.value)
    }

    @Test
    fun `an article-driven unit change to EURO locks the quantity exactly like a manual selection`() {
        // Stand-in for GoodsItemDialog's articleSelect listener, which sets unitBox.value
        // programmatically (not a browser click) after already having set the article's own
        // default quantity onto qtyField.
        val qtyField = NumberField().apply { value = 5.0 }
        val unit = unitBox().apply { value = InvoiceItemUnit.PIECE }
        wireEuroQuantityGuard(qtyField, unit) { key -> key }

        qtyField.value = 42.0 // article.defaultQuantity, applied first, like GoodsItemDialog does
        unit.value = InvoiceItemUnit.EURO // then the article's default unit

        assertEquals(1.0, qtyField.value, "the article's own default quantity must not survive a EURO unit")
        assertTrue(qtyField.isReadOnly)
        assertEquals(EURO_QUANTITY_HELPER_KEY, qtyField.helperText)
    }

    // --- applyEuroQuantityGuardState: initial bind on dialog open -----------------------------

    @Test
    fun `opening an existing item that already has unit EURO and quantity 1 applies the locked state`() {
        val qtyField = NumberField().apply { value = 1.0 }
        val unit = unitBox().apply { value = InvoiceItemUnit.EURO }

        applyEuroQuantityGuardState(qtyField, unit) { key -> key }

        assertTrue(qtyField.isReadOnly)
        assertFalse(qtyField.isInvalid)
        assertEquals(EURO_QUANTITY_HELPER_KEY, qtyField.helperText)
        assertEquals(1.0, qtyField.value)
    }

    @Test
    fun `opening a legacy item with unit EURO and quantity not 1 leaves it unlocked but invalid, without rewriting the value`() {
        val qtyField = NumberField().apply { value = 3.0 }
        val unit = unitBox().apply { value = InvoiceItemUnit.EURO }

        applyEuroQuantityGuardState(qtyField, unit) { key -> key }

        assertFalse(qtyField.isReadOnly, "the user must be able to edit the field to fix it")
        assertTrue(qtyField.isInvalid)
        assertEquals(EURO_QUANTITY_HELPER_KEY, qtyField.errorMessage)
        assertEquals(3.0, qtyField.value, "the stored legacy value must never be silently rewritten")
    }

    @Test
    fun `applyEuroQuantityGuardState is a no-op lock-wise for a non-EURO unit`() {
        val qtyField = NumberField().apply { value = 2.0; isReadOnly = true; isInvalid = true }
        val unit = unitBox().apply { value = InvoiceItemUnit.PIECE }

        applyEuroQuantityGuardState(qtyField, unit) { key -> key }

        assertFalse(qtyField.isReadOnly)
        assertFalse(qtyField.isInvalid)
        assertNull(qtyField.helperText)
        assertEquals(2.0, qtyField.value)
    }

    @Test
    fun `wiring the guard after applying the initial state does not retroactively touch a legacy invalid item`() {
        val qtyField = NumberField().apply { value = 3.0 }
        val unit = unitBox().apply { value = InvoiceItemUnit.EURO }

        applyEuroQuantityGuardState(qtyField, unit) { key -> key }
        wireEuroQuantityGuard(qtyField, unit) { key -> key }

        // Registering the listener must not itself fire a change event.
        assertFalse(qtyField.isReadOnly)
        assertTrue(qtyField.isInvalid)
        assertEquals(3.0, qtyField.value)
    }
}
