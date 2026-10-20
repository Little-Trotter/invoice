package de.littletrotter.ui.view.masterdata

import com.vaadin.flow.component.combobox.ComboBox
import de.littletrotter.application.dto.PaymentTermDto
import de.littletrotter.application.reference.port.inbound.NumberRangeQueryPort

/**
 * [SenderProfileView]'s default-payment-term combo
 * item-list bookkeeping, extracted so the view's own body stays about wiring, not this.
 */
class SenderProfileComboSupport(
    private val defaultsComposer: SenderProfileDefaultsComposer,
    private val numberRangeQueries: NumberRangeQueryPort,
) {

    fun prepareDefaultPaymentTermCombo(field: ComboBox<PaymentTermDto>, current: PaymentTermDto?) {
        val activeTerms = defaultsComposer.activePaymentTerms()
        val items = if (current != null && activeTerms.all { it.id == null || it.id != current.id }) {
            activeTerms + current
        } else {
            activeTerms
        }
        field.setItems(items)
    }

    /** Proposed when the dialog opens, before a name is typed, so a name-derived prefix starts empty. */
    fun proposeSellerNumber(): String? = numberRangeQueries.proposeSellerNumber("")
}
