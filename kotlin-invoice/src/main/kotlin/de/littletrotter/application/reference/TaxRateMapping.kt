package de.littletrotter.application.reference

import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.domain.reference.TaxRate

/** Row view of a stored tax rule. */
internal fun TaxRate.toDto(): TaxRateDto = TaxRateDto(
    id = id,
    version = version,
    name = name,
    rate = rate,
    country = country,
    validFrom = validFrom,
    validTo = validTo,
    isHotelRate = isHotelRate,
    isDefault = isDefault,
    isReference = isReference,
    code = code,
    category = category,
    exemptionReasonCode = exemptionReasonCode,
    exemptionReasonText = exemptionReasonText,
    legalBasis = legalBasis,
    isActive = isActive,
    isSelectable = isSelectable,
)

/** Copies every editable field of this row onto [rate]; id and revision stay the entity's own. */
internal fun TaxRateDto.applyTo(rate: TaxRate) {
    rate.name = name
    rate.rate = this.rate
    rate.country = country
    rate.validFrom = validFrom
    rate.validTo = validTo
    rate.isHotelRate = isHotelRate
    rate.isDefault = isDefault
    rate.isReference = isReference
    rate.code = code
    rate.category = category
    rate.exemptionReasonCode = exemptionReasonCode
    rate.exemptionReasonText = exemptionReasonText
    rate.legalBasis = legalBasis
    rate.isActive = isActive
    rate.isSelectable = isSelectable
}
