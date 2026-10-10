package de.littletrotter.application.reference

import de.littletrotter.application.dto.NumberRangeDto
import de.littletrotter.domain.reference.NumberRange

/** Row view of a stored number range. */
internal fun NumberRange.toDto(): NumberRangeDto = NumberRangeDto(
    id = id,
    version = version,
    code = code,
    displayName = displayName,
    subject = subject,
    startValue = startValue,
    prefixSource = prefixSource,
    fixedPrefix = fixedPrefix,
    prefixDataField = prefixDataField,
    datePart = datePart,
    counterWidth = counterWidth,
    resetCycle = resetCycle,
    scope = scope,
    continuity = continuity,
    isActive = isActive,
    isDefault = isDefault,
)

/** Copies every editable field of this row onto [range]; id and revision stay the entity's own. */
internal fun NumberRangeDto.applyTo(range: NumberRange) {
    range.code = code
    range.displayName = displayName
    range.subject = subject
    range.startValue = startValue
    range.prefixSource = prefixSource
    range.fixedPrefix = fixedPrefix
    range.prefixDataField = prefixDataField
    range.datePart = datePart
    range.counterWidth = counterWidth
    range.resetCycle = resetCycle
    range.scope = scope
    range.continuity = continuity
    range.isActive = isActive
    range.isDefault = isDefault
}
