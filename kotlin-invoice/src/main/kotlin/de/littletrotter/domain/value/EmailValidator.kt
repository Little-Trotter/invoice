package de.littletrotter.domain.value

/**
 * Validates e-mail addresses using a pragmatic RFC-5321/5322-inspired regex.
 *
 * The field is optional: a blank or null value is always considered valid.
 */
object EmailValidator {

    private val emailPattern = Regex("^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$")

    /** Returns null when valid or blank, otherwise a human-readable error message. */
    fun validate(email: String?): String? {
        if (email.isNullOrBlank()) return null
        return if (emailPattern.matches(email.trim())) null
        else "Ungültige E-Mail-Adresse (z. B. name@beispiel.de)."
    }

    fun isValid(email: String?): Boolean = validate(email) == null
}
