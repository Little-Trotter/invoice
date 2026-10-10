package de.littletrotter.domain.value

import java.math.BigInteger
import java.util.regex.Pattern

/**
 * Validates IBAN (International Bank Account Number) according to ISO 13616.
 *
 * Validation steps:
 * 1. Strip embedded whitespace and convert to upper-case.
 * 2. Check that the result matches the basic structure: 2 uppercase letters
 *    (country code) + 2 digits (check digits) + 1–30 alphanumeric characters
 *    (BBAN), giving a total length between 5 and 34 characters.
 * 3. Verify the MOD-97 checksum as specified in ISO 13616.
 *
 * German IBANs start with `DE` and are exactly 22 characters long.
 *
 * The field is optional: a blank or `null` value is always considered valid.
 *
 * Valid examples:
 * - `DE89370400440532013000`
 * - `DE89 3704 0044 0532 0130 00` (with spaces)
 * - `GB29NWBK60161331926819`
 */
object IbanValidator {

    /** Basic IBAN structure: 2 letters + 2 digits + 1-30 alphanumeric. */
    private val IBAN_PATTERN = Pattern.compile("^[A-Z]{2}\\d{2}[A-Z0-9]{1,30}$")

    /**
     * Validates an IBAN.
     *
     * @param iban the value to validate (may be `null` or blank)
     * @return `null` when the value is valid or blank,
     *         otherwise a human-readable error message
     */
    fun validate(iban: String?): String? {
        if (iban.isNullOrBlank()) {
            return null
        }

        val normalised = iban.trim().replace(Regex("\\s+"), "").uppercase()

        if (!IBAN_PATTERN.matcher(normalised).matches()) {
            return "Ungültige IBAN. Erwartet: Länderkürzel + 2 Prüfziffern + Kontonummer " +
                    "(z. B. DE89 3704 0044 0532 0130 00)."
        }

        if (!passesChecksum(normalised)) {
            return "IBAN-Prüfsumme ungültig. Bitte die IBAN noch einmal prüfen."
        }

        return null
    }

    /**
     * Convenience method that returns `true` when the IBAN is valid or blank.
     *
     * @param iban the value to check
     * @return `true` if the format is acceptable
     */
    fun isValid(iban: String?): Boolean {
        return validate(iban) == null
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /**
     * Verifies the MOD-97 checksum of a normalised (whitespace-free, upper-case) IBAN.
     *
     * Algorithm (ISO 13616):
     * 1. Move the first four characters to the end.
     * 2. Replace each letter with its numeric value: A↦0, ..., Z↦35
     *    (i.e. `char - 'A' + 10`).
     * 3. Interpret the resulting digit string as an integer and compute modulo 97.
     * 4. If the result is 1 the checksum is correct.
     */
    private fun passesChecksum(iban: String): Boolean {
        val rearranged = iban.substring(REARRANGE_PREFIX_LENGTH) + iban.substring(0, REARRANGE_PREFIX_LENGTH)
        val numeric = StringBuilder()

        for (c in rearranged) {
            if (c.isLetter()) {
                numeric.append(c - 'A' + LETTER_TO_DIGIT_OFFSET)
            } else {
                numeric.append(c)
            }
        }

        return BigInteger(numeric.toString()).mod(BigInteger.valueOf(MOD_97_MODULUS)) == BigInteger.ONE
    }

    /** ISO 13616 MOD-97 checksum constants — see [passesChecksum]'s own KDoc for the algorithm. */
    private const val REARRANGE_PREFIX_LENGTH = 4
    private const val LETTER_TO_DIGIT_OFFSET = 10
    private const val MOD_97_MODULUS = 97L
}
