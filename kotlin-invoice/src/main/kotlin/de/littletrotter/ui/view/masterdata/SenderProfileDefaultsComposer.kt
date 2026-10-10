package de.littletrotter.ui.view.masterdata

import de.littletrotter.application.dto.PaymentTermDto
import de.littletrotter.application.reference.port.inbound.PaymentTermQueryPort
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject

/**
 * [SenderProfileView]'s dialog-default lookup (the payment-term combo's active options),
 * extracted so the view itself does not carry the port just to feed that combo (the PO's "views
 * should not receive every port" ruling).
 */
@Dependent
class SenderProfileDefaultsComposer @Inject constructor(
    private val paymentTermQueries: PaymentTermQueryPort,
) {
    fun activePaymentTerms(): List<PaymentTermDto> = paymentTermQueries.findAllPaymentTerms().filter { it.isActive }

    /** New profiles default to immediate payment (dueInDays=0); existing profiles preserve their own setting. */
    fun defaultPaymentTermForNewProfile(): PaymentTermDto? = activePaymentTerms().firstOrNull { it.dueInDays == 0 }
}
