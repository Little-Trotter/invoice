package de.littletrotter.application.reference

import de.littletrotter.application.dto.NumberRangeDto
import de.littletrotter.domain.reference.NumberRange
import de.littletrotter.domain.reference.NumberRangeDefinition

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
    range.redefine(
        NumberRangeDefinition(
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
        ),
    )
}
