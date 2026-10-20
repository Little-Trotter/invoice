package de.littletrotter.domain.value

/**
 * Country-gated postal-code validation (L20).
 *
 * The strict German five-digit rule ([GermanPostalCodeValidator]) applies only when the
 * address's own country is Germany. Every other country gets a lenient sanity rule — non-blank
 * values up to [MAX_LENGTH] characters — because European postal formats (LU `L-1611`,
 * NL `1234 AB`, …) vary too much to model per country.
 *
 * Blank/null values are always accepted here: requiredness is a separate, contextual concern
 * of the owning form.
 */
object PostalCodeValidator {

    const val MAX_LENGTH = 10

    enum class Violation(val message: String) {
        GERMAN_FORMAT("PLZ muss aus genau 5 Ziffern bestehen (z. B. 10115)."),
        TOO_LONG("PLZ darf höchstens $MAX_LENGTH Zeichen lang sein."),
    }

    /** Country inputs are ISO alpha-2 codes ([de.littletrotter.shared.country.Country.alpha2]);
     *  a missing country counts as German — the forms' country combos default to DE. */
    fun isGermanCountry(countryAlpha2: String?): Boolean {
        val normalized = countryAlpha2?.trim()?.uppercase().orEmpty()
        return normalized.isEmpty() || normalized == "DE"
    }

    fun check(countryAlpha2: String?, postalCode: String?): Violation? {
        if (postalCode.isNullOrBlank()) {
            return null
        }
        return if (isGermanCountry(countryAlpha2)) {
            if (GermanPostalCodeValidator.isValid(postalCode)) null else Violation.GERMAN_FORMAT
        } else {
            if (postalCode.trim().length <= MAX_LENGTH) null else Violation.TOO_LONG
        }
    }

    fun isValid(countryAlpha2: String?, postalCode: String?): Boolean = check(countryAlpha2, postalCode) == null
}
