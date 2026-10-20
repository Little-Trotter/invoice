package de.littletrotter.application.reference

import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.reference.TaxRateDefinition

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

/** The editable content of this row as the domain's tax rule definition. */
internal fun TaxRateDto.toDefinition(): TaxRateDefinition = TaxRateDefinition(
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
    rate.redefine(toDefinition())
}
