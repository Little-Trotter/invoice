package de.littletrotter.ui.view.referencedata

import com.vaadin.flow.component.Component
import de.littletrotter.application.reference.port.inbound.HotelRateQueryPort
import de.littletrotter.application.reference.port.inbound.ManageHotelRateUseCase
import de.littletrotter.application.reference.port.inbound.ManageMileageRateUseCase
import de.littletrotter.application.reference.port.inbound.ManagePerDiemRateUseCase
import de.littletrotter.application.reference.port.inbound.MileageRateQueryPort
import de.littletrotter.application.reference.port.inbound.PerDiemRateQueryPort
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject

/**
 * See [AbrechnungTabFactory]'s own KDoc for why this exists. Covers
 * [ReferenceDataGroup.REISEKOSTEN] (per-diem rates, hotel rates, mileage rates).
 */
@Dependent
class ReisekostenTabFactory @Inject constructor(
    private val perDiemRateQueries: PerDiemRateQueryPort,
    private val managePerDiemRate: ManagePerDiemRateUseCase,
    private val hotelRateQueries: HotelRateQueryPort,
    private val manageHotelRate: ManageHotelRateUseCase,
    private val mileageRateQueries: MileageRateQueryPort,
    private val manageMileageRate: ManageMileageRateUseCase,
) {
    fun build(tab: ReferenceDataTab): Component = when (tab) {
        ReferenceDataTab.PER_DIEM_RATES -> PerDiemRatesTab(perDiemRateQueries, managePerDiemRate)
        ReferenceDataTab.HOTEL_RATES -> HotelRatesTab(hotelRateQueries, manageHotelRate)
        ReferenceDataTab.MILEAGE_RATES -> MileageRatesTab(mileageRateQueries, manageMileageRate)
        else -> error("ReisekostenTabFactory cannot build $tab (not in ${ReferenceDataGroup.REISEKOSTEN})")
    }
}
