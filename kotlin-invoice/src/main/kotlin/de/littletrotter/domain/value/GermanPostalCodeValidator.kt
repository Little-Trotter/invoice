package de.littletrotter.domain.value

import java.util.Optional

/**
 * Validates German postal codes (Postleitzahl / PLZ).
 * A valid German PLZ consists of exactly five digits (e.g. 10115).
 * The field is optional: a blank or null value is always considered valid.
 */
object GermanPostalCodeValidator {

    private val plzPattern = Regex("^\\d{5}$")

    fun validate(postalCode: String?): Optional<String> {
        if (postalCode.isNullOrBlank()) {
            return Optional.empty()
        }
        val stripped = postalCode.trim()
        if (!plzPattern.matches(stripped)) {
            return Optional.of("PLZ muss aus genau 5 Ziffern bestehen (z. B. 10115).")
        }
        return Optional.empty()
    }

    fun isValid(postalCode: String?): Boolean = validate(postalCode).isEmpty
}
