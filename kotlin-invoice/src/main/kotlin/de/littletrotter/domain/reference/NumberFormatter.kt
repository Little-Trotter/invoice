package de.littletrotter.domain.reference

import de.littletrotter.shared.numbering.NumberFormatter as SharedNumberFormatter
import de.littletrotter.shared.numbering.NumberPattern

/**
 * Pure formatting, no database or CDI. Deliberately separate from
 * [de.littletrotter.application.reference.NumberRangeService]: "what does the number look like"
 * is testable without a counter and repository, "which number is next" is not.
 */
object NumberFormatter {

    /** The number's shape for [range]: prefix resolved from [context], date part and counter width from the range. */
    fun pattern(range: NumberRange, context: NumberContext): NumberPattern =
        NumberPattern(resolvePrefix(range, context), range.datePart, range.counterWidth)

    fun resolvePrefix(range: NumberRange, context: NumberContext): String? = when (range.prefixSource) {
        PrefixSource.NONE -> null
        PrefixSource.FIXED -> range.fixedPrefix
        PrefixSource.SELLER_PROFILE -> context.sellerPrefix
        PrefixSource.FROM_DATA -> context.dataFieldValue
    }

    /** `'*'` for [CounterScope.GLOBAL] — a single, shared key for all sellers, deliberately not
     *  `null`: `null` in a composite counter key needs special SQL comparison (`IS NULL` instead
     *  of `=`) and is an unnecessary source of bugs. */
    fun scopeKey(range: NumberRange, context: NumberContext): String = when (range.scope) {
        CounterScope.GLOBAL -> "*"
        CounterScope.PER_SELLER -> context.sellerId?.toString()
            ?: throw IllegalArgumentException("Nummernkreis '${range.code}' ist PER_SELLER, aber der Kontext trägt keine sellerId.")
    }

    /** `''` for [ResetCycle.NEVER], otherwise the year — the reset counts per year regardless of
     *  how fine-grained the *number's* own date part is resolved. */
    fun periodKey(range: NumberRange, context: NumberContext): String =
        SharedNumberFormatter.periodKey(range.resetCycle, context.date)
}
