package de.littletrotter.shared.tax

/**
 * R4 — a typed view over a persisted tax rule code, which stays a plain `String?` column since
 * exports and backfills key off the raw string. Not persisted itself — construct from the stored
 * string at the point of use. Construction is validated without throwing: [ofOrNull] answers
 * `null` for a blank value, and each consuming module decides which error that becomes.
 */
class TaxRuleCode private constructor(val value: String) {

    override fun equals(other: Any?): Boolean = other is TaxRuleCode && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        fun ofOrNull(value: String?): TaxRuleCode? = value?.takeIf { it.isNotBlank() }?.let(::TaxRuleCode)
    }
}
