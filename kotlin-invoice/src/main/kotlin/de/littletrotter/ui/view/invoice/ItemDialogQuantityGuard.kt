package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.textfield.NumberField
import de.littletrotter.shared.catalogue.InvoiceItemUnit

/**
 * Euro² prevention at entry time — the UI-side counterpart to the domain rule
 * `LT-UNIT-02` (`de.littletrotter.domain.compliance.UnitConsistencyRules`: "Einheit EURO ⇒ BT-129
 * ist exakt 1"). With unit EURO the entered price already *is* the line amount, so any quantity
 * other than 1 multiplies a money value by another money value — the position ends up in Euro²,
 * not Euro. That rule only *detects* the defect at finalization; this file *prevents* it at entry
 * by locking the quantity field to 1 the moment EURO is picked, in the two dialogs that offer
 * EURO as a selectable unit (`GoodsItemDialog`, `ServiceItemDialog` — see each dialog's own KDoc
 * for why the others don't).
 *
 * Deliberately its own file, separate from every item dialog and from `ItemDialogB2bSupport.kt`
 * (unrelated concern), following the same "small, dialog-independent file" idiom as
 * `LineAdvisor.kt`.
 */

/** Shared i18n key for the quantity field's locked-state helper text (see [wireEuroQuantityGuard]
 *  point 1) — one key for every dialog that wires this guard. */
internal const val EURO_QUANTITY_HELPER_KEY = "invoiceDetail.itemDialog.quantity.euroLocked"

/**
 * Applies the guard's state to [qtyField]/[unitBox] once, from whatever values they already carry
 * — without listening for a change and without ever overwriting [qtyField]'s value. Call this
 * exactly once per dialog, after an existing item's stored quantity/unit have been bound to the
 * two fields (or, for a brand-new item, once the fields sit at their initial defaults), and
 * *before* [wireEuroQuantityGuard] takes over for future changes.
 *
 * Three states:
 * - unit != EURO: unlocked, no helper text (nothing to guard).
 * - unit == EURO and quantity is already exactly 1: locked, same as a live guard hit — this is the
 *   normal case for an item saved after this guard existed.
 * - unit == EURO and quantity != 1 (pre-fix legacy data): left **unlocked** but marked invalid, so
 *   the user sees the problem and can fix it themselves (set quantity to 1) — the stored value is
 *   never silently rewritten on open.
 */
internal fun applyEuroQuantityGuardState(qtyField: NumberField, unitBox: ComboBox<InvoiceItemUnit>, tr: (String) -> String) {
    if (unitBox.value != InvoiceItemUnit.EURO) {
        qtyField.isReadOnly = false
        qtyField.isInvalid = false
        qtyField.helperText = null
        return
    }
    val qty = qtyField.value
    if (qty != null && qty == 1.0) {
        qtyField.isReadOnly = true
        qtyField.isInvalid = false
        qtyField.helperText = tr(EURO_QUANTITY_HELPER_KEY)
    } else {
        // Legacy item predating this guard: unit EURO with a quantity != 1 already persisted.
        // Never rewritten here — only flagged, so the user notices and corrects it themselves.
        qtyField.isReadOnly = false
        qtyField.isInvalid = true
        qtyField.errorMessage = tr(EURO_QUANTITY_HELPER_KEY)
    }
}

/**
 * Wires [qtyField] to lock at exactly 1 whenever [unitBox] carries (or becomes) EURO, and to
 * unlock again the moment it changes away from EURO — covering both ways a dialog can set the
 * unit to EURO after this is wired: the user picking it directly in [unitBox], or an article
 * selection listener applying the article's default unit programmatically. Call once
 * [applyEuroQuantityGuardState] has already established the correct state for an existing item
 * (see that function's KDoc for why the ordering matters — this listener always force-resets the
 * quantity to 1, which would silently rewrite a legacy invalid value if it fired during that
 * initial bind).
 */
internal fun wireEuroQuantityGuard(qtyField: NumberField, unitBox: ComboBox<InvoiceItemUnit>, tr: (String) -> String) {
    unitBox.addValueChangeListener { event ->
        if (event.value == InvoiceItemUnit.EURO) {
            qtyField.value = 1.0
            qtyField.isReadOnly = true
            qtyField.isInvalid = false
            qtyField.helperText = tr(EURO_QUANTITY_HELPER_KEY)
        } else {
            qtyField.isReadOnly = false
            qtyField.isInvalid = false
            qtyField.helperText = null
        }
    }
}
