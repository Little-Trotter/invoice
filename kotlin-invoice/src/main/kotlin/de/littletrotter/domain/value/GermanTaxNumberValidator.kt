package de.littletrotter.domain.value

import java.util.Optional

/**
 * Validates German tax numbers (Steuernummer).
 *
 * After stripping whitespace and slash separators, the normalised digit sequence
 * must be 10 to 13 digits long. The field is optional.
 */
object GermanTaxNumberValidator {

    private val normalisedPattern = Regex("^\\d{10,13}$")

    fun validate(taxNumber: String?): Optional<String> {
        if (taxNumber.isNullOrBlank()) {
            return Optional.empty()
        }
        val normalised = taxNumber.trim().replace(Regex("[\\s/]"), "")
        if (!normalisedPattern.matches(normalised)) {
            return Optional.of("Steuernummer muss 10-13 Ziffern enthalten (z. B. 181/815/08155 oder 1118150815).")
        }
        return Optional.empty()
    }

    fun isValid(taxNumber: String?): Boolean = validate(taxNumber).isEmpty
}
