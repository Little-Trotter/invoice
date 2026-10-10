package de.littletrotter.domain.value

/**
 * ISO 9362 BIC/SWIFT format validation: 8 characters (party prefix) or 11 (+ branch
 * code). Positions 1-4: institution code (letters). Positions 5-6: ISO 3166-1 country letters.
 * Positions 7-8: location code (letters or digits). Positions 9-11 (optional): branch code
 * (letters or digits). Purely format-based, like [VatIdValidator] — no bank-directory lookup.
 */
object BicValidator {

    private val PATTERN = Regex("^[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$")

    /**
     * Returns `null` when [bic] is blank/null (optional field, requiredness is a separate,
     * contextual concern) or matches the ISO 9362 shape, otherwise a human-readable error.
     */
    fun validate(bic: String?): String? {
        if (bic.isNullOrBlank()) {
            return null
        }
        val normalised = bic.trim().uppercase()
        return if (PATTERN.matches(normalised)) {
            null
        } else {
            "BIC muss aus 8 oder 11 alphanumerischen Zeichen bestehen, Positionen 5-6 = " +
                "Länderkennung (z. B. DEUTDEFF oder DEUTDEFF500)."
        }
    }

    fun isValid(bic: String?): Boolean = validate(bic) == null
}
