package de.littletrotter.ui.view.referencedata

import com.vaadin.flow.component.Component
import de.littletrotter.application.quote.port.inbound.QuoteAvailabilityQueryPort
import de.littletrotter.application.reference.port.inbound.ManageNumberRangeUseCase
import de.littletrotter.application.reference.port.inbound.ManageTaxRateUseCase
import de.littletrotter.application.reference.port.inbound.NumberRangeQueryPort
import de.littletrotter.application.reference.port.inbound.TaxRateQueryPort
import de.littletrotter.shared.time.TimeSource
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject

/**
 * [ReferenceDataView] does not receive every reference-data port just to hand them to its tabs;
 * each [ReferenceDataGroup] gets its own narrow CDI factory that injects only that group's own
 * ports, and the view injects the three group factories instead. This one covers
 * [ReferenceDataGroup.ABRECHNUNG] (payment terms, tax rates, units, number ranges); the payment
 * terms tab has its own factory because it also needs the languages and the multilingual switch.
 */
@Dependent
class AbrechnungTabFactory @Inject constructor(
    private val paymentTermsTabFactory: PaymentTermsTabFactory,
    private val taxRateQueries: TaxRateQueryPort,
    private val manageTaxRate: ManageTaxRateUseCase,
    private val numberRangeQueries: NumberRangeQueryPort,
    private val manageNumberRange: ManageNumberRangeUseCase,
    private val quoteAvailability: QuoteAvailabilityQueryPort,
    private val time: TimeSource,
) {
    fun build(tab: ReferenceDataTab): Component = when (tab) {
        ReferenceDataTab.PAYMENT_TERMS -> paymentTermsTabFactory.build()
        ReferenceDataTab.TAX_RATES -> TaxRatesTab(taxRateQueries, manageTaxRate, time.today())
        ReferenceDataTab.UNITS -> UnitsTab()
        ReferenceDataTab.NUMBER_RANGES -> NumberRangesTab(numberRangeQueries, manageNumberRange, quoteAvailability)
        else -> error("AbrechnungTabFactory cannot build $tab (not in ${ReferenceDataGroup.ABRECHNUNG})")
    }
}
