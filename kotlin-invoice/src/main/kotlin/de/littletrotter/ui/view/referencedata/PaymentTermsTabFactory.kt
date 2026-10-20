package de.littletrotter.ui.view.referencedata

import de.littletrotter.application.invoice.port.inbound.MultilingualTextsAvailabilityQueryPort
import de.littletrotter.application.reference.port.inbound.LanguageQueryPort
import de.littletrotter.application.reference.port.inbound.ManagePaymentTermUseCase
import de.littletrotter.application.reference.port.inbound.PaymentTermQueryPort
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject

/** Builds the payment terms tab for [AbrechnungTabFactory] with exactly the ports that tab uses. */
@Dependent
class PaymentTermsTabFactory @Inject constructor(
    private val paymentTermQueries: PaymentTermQueryPort,
    private val managePaymentTerm: ManagePaymentTermUseCase,
    private val languageQueries: LanguageQueryPort,
    private val multilingualTextsFeatureConfig: MultilingualTextsAvailabilityQueryPort,
) {
    fun build(): PaymentTermsTab =
        PaymentTermsTab(paymentTermQueries, managePaymentTerm, languageQueries, multilingualTextsFeatureConfig)
}
