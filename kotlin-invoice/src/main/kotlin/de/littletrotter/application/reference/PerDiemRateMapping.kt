package de.littletrotter.application.reference

import de.littletrotter.application.dto.PerDiemRateDto
import de.littletrotter.domain.reference.PerDiemRate

/** Row view of a stored per-diem rate. */
internal fun PerDiemRate.toDto(): PerDiemRateDto = PerDiemRateDto(
    id = id,
    version = version,
    country = country,
    region = region,
    fullDayRate = fullDayRate,
    arrivalDepartureRate = arrivalDepartureRate,
    inlandRules = inlandRules,
    isDefault = isDefault,
    validFrom = validFrom,
    validTo = validTo,
    isActive = isActive,
    source = source,
)

/** Copies every editable field of this row onto [rate]; id and revision stay the entity's own. */
internal fun PerDiemRateDto.applyTo(rate: PerDiemRate) {
    rate.country = country
    rate.region = region
    rate.fullDayRate = fullDayRate
    rate.arrivalDepartureRate = arrivalDepartureRate
    rate.inlandRules = inlandRules
    rate.isDefault = isDefault
    rate.validFrom = validFrom
    rate.validTo = validTo
    rate.isActive = isActive
    rate.source = source
}
