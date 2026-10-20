package de.littletrotter.application.dto

import de.littletrotter.domain.reference.Continuity
import de.littletrotter.domain.reference.CounterScope
import de.littletrotter.shared.numbering.DatePart
import de.littletrotter.domain.reference.NumberRangeSubject
import de.littletrotter.domain.reference.PrefixDataField
import de.littletrotter.domain.reference.PrefixSource
import de.littletrotter.shared.numbering.ResetCycle

/**
 * One number range: what the number-range grid shows and what its editor saves. [id] `null`
 * marks a new range; [version] is the row's optimistic-lock revision, checked on save.
 */
data class NumberRangeDto(
    val id: Long? = null,
    val version: Long? = null,
    val code: String = "",
    val displayName: String = "",
    val subject: NumberRangeSubject = NumberRangeSubject.CUSTOMER,
    val startValue: Long = 1,
    val prefixSource: PrefixSource = PrefixSource.NONE,
    val fixedPrefix: String? = null,
    val prefixDataField: PrefixDataField? = null,
    val datePart: DatePart = DatePart.NONE,
    val counterWidth: Int = 5,
    val resetCycle: ResetCycle = ResetCycle.NEVER,
    val scope: CounterScope = CounterScope.GLOBAL,
    val continuity: Continuity = Continuity.UNIQUE_ONLY,
    val isActive: Boolean = true,
    val isDefault: Boolean = false,
)
