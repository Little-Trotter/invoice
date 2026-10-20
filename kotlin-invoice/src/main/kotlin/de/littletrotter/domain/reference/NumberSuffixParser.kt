package de.littletrotter.domain.reference

/**
 * Pure counterpart to [de.littletrotter.shared.numbering.NumberFormatter] for the reverse direction — recovers the numeric counter
 * suffix a persisted number string ends with, without knowing prefix, date part, or counter
 * width (the same prefix-agnostic reading `currentValue` already relies on for its
 * `MAX(last_value)` overview — see [de.littletrotter.domain.repository.NumberCounterRepository]).
 *
 * Backs the "synchronize number counters to existing data" admin maintenance action
 * ([de.littletrotter.application.masterdata.MasterDataNumberService]): rows written past the
 * service (bulk import, datagen, hand-typed numbers) never move `number_counter`, so the highest
 * actually-issued number has to be recovered from the persisted strings themselves.
 */
object NumberSuffixParser {

    private val trailingDigits = Regex("(\\d+)$")

    /**
     * The trailing run of digits in [number] as a [Long], or `null` if [number] is blank or ends
     * without any digit at all (a hand-typed number that does not follow the range's pattern —
     * left out of the max rather than treated as 0, which would wrongly lower a healthy counter
     * via [maxSuffix]).
     */
    fun suffixOf(number: String?): Long? {
        if (number.isNullOrBlank()) return null
        return trailingDigits.find(number)?.groupValues?.get(1)?.toLongOrNull()
    }

    /**
     * The highest [suffixOf] across [numbers], or `null` if none parse (including an empty
     * collection) — "nothing observed to raise the counter to".
     */
    fun maxSuffix(numbers: Iterable<String?>): Long? = numbers.mapNotNull(::suffixOf).maxOrNull()
}
