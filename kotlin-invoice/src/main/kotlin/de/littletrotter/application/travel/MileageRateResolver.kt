package de.littletrotter.application.travel

import de.littletrotter.application.travel.port.inbound.MileageRatePrefillQueryPort
import de.littletrotter.domain.reference.MileageRate
import de.littletrotter.domain.reference.VehicleType
import de.littletrotter.domain.repository.MileageRateRepository
import de.littletrotter.shared.Money
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The mileage-rate lookup de.littletrotter.ui.view.invoice.TravelItemDialog used to run
 * directly against [de.littletrotter.application.reference.port.inbound.MileageRateQueryPort]: pulled out here
 * for the same DRY reason as [HotelRateResolver]. The rate
 * depends on the vehicle type and the travel date, never the destination country.
 */
@ApplicationScoped
class MileageRateResolver @Inject constructor(
    private val mileageRateRepository: MileageRateRepository,
) : MileageRatePrefillQueryPort {
    /** The active rate per km for [vehicleType] on [date], or `null` when no rate is configured —
     *  callers keep whatever value the user already entered in that case. */
    fun resolveRatePerKm(vehicleType: VehicleType, date: LocalDate): MileageRate? =
        mileageRateRepository.findByVehicleTypeAndDate(vehicleType, date)

    /** `kilometers x ratePerKm`, via [MileageRate.calculateAmount] — one shared formula, no second
     *  multiply reimplemented here or in a dialog. */
    fun calculateAmount(rate: MileageRate, kilometers: BigDecimal): Money = rate.calculateAmount(kilometers)

    override fun ratePerKm(vehicleType: VehicleType, date: LocalDate): BigDecimal? =
        resolveRatePerKm(vehicleType, date)?.ratePerKm
}
