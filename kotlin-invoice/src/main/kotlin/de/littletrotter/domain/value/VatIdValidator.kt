package de.littletrotter.domain.value

import de.littletrotter.shared.country.Country

/**
 * Validates VAT ID formats for EU member states, the United Kingdom, and Norway.
 * The validation is purely format-based (regex); it does not call the EU VIES
 * online service. All patterns require the country-code prefix to be upper-case and
 * no embedded whitespace (strip the value before calling `validate()`).
 */
object VatIdValidator {

    /**
     * Regex patterns keyed by the VAT-number country prefix (ISO-3166-1 alpha-2,
     * except Greece which uses "EL" as its EU VAT prefix).
     */
    private val COUNTRY_PATTERNS: Map<String, Regex> = mapOf(
        // EU member states
        "AT" to "^ATU\\d{8}$".toRegex(),
        "BE" to "^BE[01]\\d{9}$".toRegex(),
        "BG" to "^BG\\d{9,10}$".toRegex(),
        "CY" to "^CY\\d{8}[A-Z]$".toRegex(),
        "CZ" to "^CZ\\d{8,10}$".toRegex(),
        "DE" to "^DE\\d{9}$".toRegex(),
        "DK" to "^DK\\d{8}$".toRegex(),
        "EE" to "^EE\\d{9}$".toRegex(),
        "EL" to "^EL\\d{9}$".toRegex(),  // Greece
        "ES" to "^ES[A-Z0-9]\\d{7}[A-Z0-9]$".toRegex(),
        "FI" to "^FI\\d{8}$".toRegex(),
        "FR" to "^FR[A-Z0-9]{2}\\d{9}$".toRegex(),
        "HR" to "^HR\\d{11}$".toRegex(),
        "HU" to "^HU\\d{8}$".toRegex(),
        "IE" to "^IE(\\d{7}[A-W]|\\d[A-Z+*]\\d{5}[A-W])$".toRegex(),
        "IT" to "^IT\\d{11}$".toRegex(),
        "LT" to "^LT(\\d{9}|\\d{12})$".toRegex(),
        "LU" to "^LU\\d{8}$".toRegex(),
        "LV" to "^LV\\d{11}$".toRegex(),
        "MT" to "^MT\\d{8}$".toRegex(),
        "NL" to "^NL\\d{9}B\\d{2}$".toRegex(),
        "PL" to "^PL\\d{10}$".toRegex(),
        "PT" to "^PT\\d{9}$".toRegex(),
        "RO" to "^RO\\d{2,10}$".toRegex(),
        "SE" to "^SE\\d{12}$".toRegex(),
        "SI" to "^SI\\d{8}$".toRegex(),
        "SK" to "^SK\\d{10}$".toRegex(),
        // Non-EU countries with recognised VAT-ID formats
        "GB" to "^GB(\\d{9}|\\d{12}|GD\\d{3}|HA\\d{3})$".toRegex(),
        "NO" to "^NO\\d{9}MVA$".toRegex(),
        // Additional non-EU tax id formats
        "US" to "^US\\d{2}-?\\d{7}$".toRegex(),
        "CA" to "^CA\\d{9}(RT\\d{4})?$".toRegex(),
        "AU" to "^AU\\d{11}$".toRegex(),
        "NZ" to "^NZ\\d{8,9}$".toRegex(),
        // Switzerland: Country.fromVatIdPrefix's own 2-character parsing (not the
        // official 3-letter "CHE" government prefix) is what this codebase's third-country tax
        // scenarios (TaxRuleResolver's UNSUPPORTED_TAX_CASE / Blocked resolution) already key
        // off; the 9-digit UID number itself is unchanged.
        "CH" to "^CH\\d{9}$".toRegex(),
    )

    /**
     * One example VAT id per prefix in [COUNTRY_PATTERNS], each guaranteed (see
     * `VatIdValidatorTest`) to pass [validate] — used as the field placeholder and inside the
     * [Violation.FORMAT] message.
     */
    private val EXAMPLES: Map<String, String> = mapOf(
        "AT" to "ATU12345678",
        "BE" to "BE0123456789",
        "BG" to "BG123456789",
        "CY" to "CY12345678A",
        "CZ" to "CZ12345678",
        "DE" to "DE123456789",
        "DK" to "DK12345678",
        "EE" to "EE123456789",
        "EL" to "EL123456789",
        "ES" to "ESA1234567A",
        "FI" to "FI12345678",
        "FR" to "FRXX123456789",
        "HR" to "HR12345678901",
        "HU" to "HU12345678",
        "IE" to "IE1234567T",
        "IT" to "IT12345678901",
        "LT" to "LT123456789",
        "LU" to "LU12345678",
        "LV" to "LV12345678901",
        "MT" to "MT12345678",
        "NL" to "NL123456789B01",
        "PL" to "PL1234567890",
        "PT" to "PT123456789",
        "RO" to "RO123456789",
        "SE" to "SE123456789012",
        "SI" to "SI12345678",
        "SK" to "SK1234567890",
        "GB" to "GB123456789",
        "NO" to "NO123456789MVA",
        "US" to "US12-3456789",
        "CA" to "CA123456789",
        "AU" to "AU12345678901",
        "NZ" to "NZ12345678",
        "CH" to "CH123456789",
    )

    /**
     * Wave E (PO decision, 2026-08-30 evening) — matches `sender_profile.vat_id`/
     * `recipient.vat_id`'s column width (`V1__baseline_schema.sql`, raised from 30). No
     * recognised format in [COUNTRY_PATTERNS] comes anywhere near this length; the guard exists
     * so a pathological input gets one clear, specific error message here instead of an opaque
     * database "value too long" failure at save time — official foreign VAT-id formats are all
     * far shorter and pass through unclamped either way. Public: the UI layer needs the number
     * itself to build the [Violation.TOO_LONG] message parameter.
     */
    const val MAX_LENGTH = 64

    /**
     * The VAT prefixes VIES actually answers for: the EU member states. Everything else in
     * [COUNTRY_PATTERNS] (GB, NO, US, CA, AU, NZ) has a recognised format but no VIES entry —
     * querying those is a guaranteed dead end, so callers can refuse before the HTTP call instead
     * of reporting the service as unreachable.
     *
     * "EL" rather than "GR" on purpose: Greece's VAT prefix differs from its ISO-3166 code, and
     * VIES speaks the VAT prefix.
     */
    private val VIES_COUNTRY_PREFIXES: Set<String> = setOf(
        "AT", "BE", "BG", "CY", "CZ", "DE", "DK", "EE", "EL", "ES", "FI", "FR", "HR", "HU",
        "IE", "IT", "LT", "LU", "LV", "MT", "NL", "PL", "PT", "RO", "SE", "SI", "SK",
    )

    /** The four ways a non-blank VAT id can fail format validation — mirrors
     *  [PostalCodeValidator.Violation]'s shape. A caller resolves the user-facing wording (i18n,
     *  with parameters such as the prefix actually read or the country's own example) rather than
     *  this enum carrying a fixed sentence itself. */
    enum class Violation { TOO_LONG, NO_PREFIX, UNKNOWN_PREFIX, FORMAT }

    /**
     * Returns the set of supported country-code prefixes.
     *
     * @return unmodifiable view of the supported prefix set
     */
    fun supportedCountryPrefixes(): Set<String> = COUNTRY_PATTERNS.keys

    /** Whether VIES can be asked about this (already normalised) VAT id at all. */
    fun isViesCountry(vatId: String?): Boolean = prefixOf(vatId) in VIES_COUNTRY_PREFIXES

    /**
     * The one normalisation every caller must share: outer trim, ALL inner whitespace removed,
     * upper-cased.
     *
     * Public because it used to be private, and that was the bug: [validate] normalised
     * internally and then reported the input valid, while `RecipientView.verifyVatId` split the
     * RAW input into country code and number and handed the number — spaces and all — to
     * [de.littletrotter.infrastructure.vies.ViesHttpClient], which builds it into a URL path.
     * `URI.create` rejects the space, the surrounding catch turned that into UNREACHABLE, and the
     * user was told VIES was down when in truth "DE 136 695 976" had never been cleaned up. The
     * validator's own error text promises exactly this normalisation ("Leerzeichen werden
     * entfernt"), so there must be one function that delivers it.
     *
     * `\p{Z}` alongside `\s`: Java's `\s` does not cover the non-breaking space, and a VAT id
     * copied out of a web page or a PDF frequently carries one.
     */
    fun normalize(vatId: String?): String =
        vatId?.replace(Regex("[\\s\\p{Z}]+"), "")?.uppercase().orEmpty()

    /**
     * The VAT country prefix carried by the number ITSELF — never the customer's address country.
     * A German company invoicing from an Austrian registration types an AT number, and that is
     * what VIES has to be asked about. Empty when the value is too short to carry one.
     */
    fun prefixOf(vatId: String?): String = normalize(vatId).take(2).takeIf { it.length == 2 }.orEmpty()

    /** The number without its country prefix, normalised — what VIES expects as the second path
     *  segment. Empty when the value carries no prefix. */
    fun numberOf(vatId: String?): String =
        normalize(vatId).takeIf { it.length > 2 }?.drop(2).orEmpty()

    /**
     * The VAT prefix [country] would carry, when [country] has a recognised pattern in
     * [COUNTRY_PATTERNS] — the ISO-3166-1 alpha-2 code, except Greece, whose EU VAT prefix "EL"
     * differs from its own alpha-2 "GR". `null` for a country with no recognised VAT-id format
     * at all (e.g. CH, IN).
     */
    fun vatPrefixFor(country: Country): String? {
        val prefix = if (country == Country.GR) "EL" else country.alpha2
        return prefix.takeIf { it in COUNTRY_PATTERNS }
    }

    /**
     * A well-formed example VAT id for [prefix] (already-normalised, e.g. "DE"), guaranteed by
     * `VatIdValidatorTest` to itself pass [validate]. Falls back to the German example for a
     * [prefix] this validator does not recognise, so callers always get a usable placeholder.
     */
    fun exampleFor(prefix: String): String = EXAMPLES[normalize(prefix)] ?: EXAMPLES.getValue("DE")

    /**
     * The structured reason a non-blank, normalised VAT id fails format validation, or `null`
     * when it is valid (or blank). See [Violation] for what each value means and [validate] for
     * the human-readable sentence built from it.
     */
    fun violationOf(vatId: String?): Violation? {
        if (vatId.isNullOrBlank()) return null
        val normalised = normalize(vatId)
        val pattern = COUNTRY_PATTERNS[normalised.take(2)]
        return when {
            normalised.length > MAX_LENGTH -> Violation.TOO_LONG
            normalised.length < 2 -> Violation.NO_PREFIX
            pattern == null -> Violation.UNKNOWN_PREFIX
            !pattern.matches(normalised) -> Violation.FORMAT
            else -> null
        }
    }

    /**
     * Validates the format of a VAT ID.
     *
     * The value is normalised (whitespace stripped, upper-cased) before matching.
     * An empty or `null` value is considered valid (field is optional).
     *
     * @param vatId the VAT ID to validate (may be `null` or blank)
     * @return `null` when the format is valid (or input is blank),
     *         otherwise a human-readable error message
     */
    fun validate(vatId: String?): String? {
        val violation = violationOf(vatId) ?: return null
        val normalised = normalize(vatId)
        val prefix = normalised.take(2)
        return when (violation) {
            Violation.TOO_LONG ->
                "USt-IdNr. darf höchstens $MAX_LENGTH Zeichen umfassen (aktuell: ${normalised.length})."
            Violation.NO_PREFIX ->
                "USt-IdNr. muss mindestens den 2-stelligen Länderpräfix enthalten (z. B. DE123456789)."
            Violation.UNKNOWN_PREFIX ->
                "Unbekannter Länderpräfix \"$prefix\". Unterstützt werden EU-Mitgliedstaaten, GB, NO, US, CA, AU und NZ. Beispiel: DE123456789."
            Violation.FORMAT ->
                "USt-IdNr. \"$normalised\" ist ungültig für $prefix. Erwartetes Format (Beispiel): ${exampleFor(prefix)}. Leerzeichen werden entfernt, also sind Eingaben wie 'DE 123 456 789' erlaubt."
        }
    }

    /**
     * Convenience method that returns `true` when the VAT ID format is valid
     * (or the value is blank/null).
     *
     * @param vatId the VAT ID to check
     * @return `true` if the format is acceptable
     */
    fun isValid(vatId: String?): Boolean = validate(vatId) == null
}
