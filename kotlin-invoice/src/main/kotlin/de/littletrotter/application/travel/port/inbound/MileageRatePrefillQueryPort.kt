package de.littletrotter.application.travel.port.inbound

import de.littletrotter.domain.reference.VehicleType
import java.math.BigDecimal
import java.time.LocalDate

/** Looks up the mileage rate per kilometre that pre-fills a trip expense row. */
fun interface MileageRatePrefillQueryPort {

    /** The active rate per km for [vehicleType] on [date], `null` when none is configured. */
    fun ratePerKm(vehicleType: VehicleType, date: LocalDate): BigDecimal?
}
