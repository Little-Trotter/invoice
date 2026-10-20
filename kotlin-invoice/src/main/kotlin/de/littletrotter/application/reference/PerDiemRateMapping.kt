package de.littletrotter.application.reference

import de.littletrotter.application.dto.PerDiemRateDto
import de.littletrotter.domain.reference.PerDiemRate
import de.littletrotter.domain.reference.PerDiemRateDefinition

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
    rate.redefine(
        PerDiemRateDefinition(
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
        ),
    )
}
