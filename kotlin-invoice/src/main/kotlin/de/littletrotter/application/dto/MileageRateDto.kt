package de.littletrotter.application.dto

import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.VehicleType
import java.math.BigDecimal
import java.time.LocalDate

/**
 * One row of the mileage-rate catalogue: what the catalogue grid shows and what its editor saves.
 * [id] `null` marks a new row; [version] is the row's optimistic-lock revision, checked on save.
 */
data class MileageRateDto(
    val id: Long? = null,
    val version: Long? = null,
    val country: Country? = null,
    val vehicleType: VehicleType? = null,
    val ratePerKm: BigDecimal = BigDecimal.ZERO,
    val validFrom: LocalDate? = null,
    val validTo: LocalDate? = null,
    val isActive: Boolean = true,
    val source: String? = null,
)
