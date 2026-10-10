package de.littletrotter.shared.document

/**
 * The language a generated document (invoice, quote, any later document) is rendered in. Exactly
 * one value per recipient decides it for every report; "international" is English.
 *
 * [code] is the persisted value and matches `language.code`.
 */
enum class DocumentLanguage(val code: String) {
    DE("de"),
    EN("en"),
    ;

    companion object {
        /** Countries whose customers get German documents; every other country gets English. */
        private val GERMAN_LANGUAGE_COUNTRIES: Set<String> = setOf("DE", "AT", "CH", "LI")

        /**
         * The default for a new recipient. A missing or blank country reads as German (the
         * domestic convention for an unset country). Only a starting value: the recipient's flag
         * can be changed afterwards, and a country edit does not touch it.
         */
        fun fromCountry(alpha2: String?): DocumentLanguage =
            if (alpha2.isNullOrBlank() || alpha2.uppercase() in GERMAN_LANGUAGE_COUNTRIES) DE else EN

        /** Exactly `"en"` is English; `null`, blank and unknown codes read as German. */
        fun fromCode(code: String?): DocumentLanguage = if (code == EN.code) EN else DE

        /** The render language (`"de"`/`"en"`) for a frozen document language code. */
        fun renderCode(code: String?): String = fromCode(code).code
    }
}
