package de.littletrotter.domain.value

import java.util.regex.Pattern

/**
 * Validates phone numbers according to German conventions (DIN 5008).
 *
 * Allowed characters: digits, `+` (leading only), spaces, hyphens (`-`),
 * slashes (`/`), and parentheses. The normalised digit sequence must contain
 * between 7 and 15 digits, in line with the ITU-T E.164 standard.
 *
 * The field is optional: a blank or null value is always considered valid.
 */
object PhoneValidator {

    private val ALLOWED_CHARS: Pattern = Pattern.compile("[+0-9\\s\\-/().]+")

    /** ITU-T E.164 minimum/maximum normalised digit count (see this object's KDoc). */
    private const val MIN_DIGIT_COUNT = 7
    private const val MAX_DIGIT_COUNT = 15

    /**
     * Returns null when valid or blank, otherwise a human-readable error message.
     */
    @JvmStatic
    fun validate(phone: String?): String? {
        if (phone.isNullOrBlank()) return null
        val stripped = phone.trim()
        val digitCount = stripped.count { it.isDigit() }
        return when {
            !ALLOWED_CHARS.matcher(stripped).matches() ->
                "Telefonnummer enthält ungültige Zeichen. Erlaubt sind: Ziffern, +, -, /, (, ), Leerzeichen."
            digitCount < MIN_DIGIT_COUNT -> "Telefonnummer muss mindestens $MIN_DIGIT_COUNT Ziffern enthalten."
            digitCount > MAX_DIGIT_COUNT -> "Telefonnummer darf maximal $MAX_DIGIT_COUNT Ziffern enthalten."
            else -> null
        }
    }

    /** Returns true when valid or blank. */
    @JvmStatic
    fun isValid(phone: String?): Boolean = validate(phone) == null
}
