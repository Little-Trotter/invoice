package de.littletrotter.application.reference

import de.littletrotter.application.dto.HotelRateDto
import de.littletrotter.application.dto.MileageRateDto
import de.littletrotter.domain.reference.HotelRate
import de.littletrotter.domain.reference.HotelRateDefinition
import de.littletrotter.domain.reference.MileageRate
import de.littletrotter.domain.reference.MileageRateDefinition

/** Row view of a stored hotel rate. */
internal fun HotelRate.toDto(): HotelRateDto = HotelRateDto(
    id = id,
    version = version,
    country = country,
    region = region,
    ratePerNight = ratePerNight,
    validFrom = validFrom,
    validTo = validTo,
    isActive = isActive,
    source = source,
)

/** Copies every editable field of this row onto [rate]; id and revision stay the entity's own. */
internal fun HotelRateDto.applyTo(rate: HotelRate) {
    rate.redefine(
        HotelRateDefinition(
            country = country,
            region = region,
            ratePerNight = ratePerNight,
            validFrom = validFrom,
            validTo = validTo,
            isActive = isActive,
            source = source,
        ),
    )
}

/** Row view of a stored mileage rate. */
internal fun MileageRate.toDto(): MileageRateDto = MileageRateDto(
    id = id,
    version = version,
    country = country,
    vehicleType = vehicleType,
    ratePerKm = ratePerKm,
    validFrom = validFrom,
    validTo = validTo,
    isActive = isActive,
    source = source,
)

/** Copies every editable field of this row onto [rate]; id and revision stay the entity's own. */
internal fun MileageRateDto.applyTo(rate: MileageRate) {
    rate.redefine(
        MileageRateDefinition(
            country = country,
            vehicleType = vehicleType,
            ratePerKm = ratePerKm,
            validFrom = validFrom,
            validTo = validTo,
            isActive = isActive,
            source = source,
        ),
    )
}
