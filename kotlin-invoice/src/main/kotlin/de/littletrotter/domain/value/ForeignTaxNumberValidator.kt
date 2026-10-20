package de.littletrotter.domain.value

import de.littletrotter.shared.country.Country
import java.util.Optional

/**
 * Validates the national tax number a seller holds in a foreign country of VAT registration.
 *
 * The number is first normalised (separators removed, upper case) because users type it in many
 * layouts, and only the normalised form is checked and stored. Which structure and check digit
 * apply is decided per country in [ForeignTaxNumberRules]. Error results are i18n keys so the
 * caller can render them in the user's language together with [formatHint]; callers treat them as a
 * warning, not as a block.
 */
object ForeignTaxNumberValidator {

    const val REQUIRED_KEY = "validation.foreignTaxNumber.required"
    const val UNSUPPORTED_KEY = "validation.foreignTaxNumber.unsupported"
    const val INVALID_KEY = "validation.foreignTaxNumber.invalid"

    const val LABEL_SIREN = "foreignTaxNumber.label.siren"
    const val LABEL_N_FISCAL = "foreignTaxNumber.label.nFiscal"
    const val LABEL_KBO = "foreignTaxNumber.label.kbo"
    const val LABEL_RRN = "foreignTaxNumber.label.rrn"
    const val LABEL_RSIN = "foreignTaxNumber.label.rsin"
    const val LABEL_BSN = "foreignTaxNumber.label.bsn"
    const val LABEL_MATRICULE = "foreignTaxNumber.label.matricule"
    const val LABEL_TIN = "foreignTaxNumber.label.tin"

    private const val FR_SIREN_DIGITS = 9
    private const val BE_KBO_DIGITS = 10
    private const val VAT_PREFIX_LENGTH = 2
    private const val BE_SHORT_DIGITS = 9

    private val nlVat = Regex("(\\d{9})B\\d{2}")
    private val separators = Regex("[\\s./-]")

    fun normalize(raw: String): String = raw.replace(separators, "").uppercase()

    fun validate(country: Country, taxNumber: String?): Optional<String> {
        val normalised = normalize(taxNumber.orEmpty())
        val rule = ForeignTaxNumberRules.byCountry[country]
        val key = when {
            normalised.isEmpty() -> REQUIRED_KEY
            rule == null -> UNSUPPORTED_KEY
            !rule.accepts(normalised) -> INVALID_KEY
            else -> null
        }
        return Optional.ofNullable(key)
    }

    fun isValid(country: Country, taxNumber: String?): Boolean = validate(country, taxNumber).isEmpty

    /** Short German description of the expected format, empty for a country without a rule. */
    fun formatHint(country: Country): String = ForeignTaxNumberRules.byCountry[country]?.formatHint.orEmpty()

    /**
     * i18n key of the label printed with the number. Countries with their own term get it per
     * recognised format (France SIREN/N° fiscal, Belgium KBO/RRN, Luxembourg Matricule); every
     * other country and every unrecognised format gets the generic `TIN (<alpha-2>)` key, the
     * caller supplies the alpha-2 code as `{0}`. Netherlands: RSIN (company) and BSN (person) are
     * both 9 digits with the same 11-proof, so the format cannot tell them apart; the number is
     * labelled RSIN because the seller registration is a business registration.
     */
    fun labelKey(country: Country, taxNumber: String): String {
        val normalised = normalize(taxNumber)
        val recognised = ForeignTaxNumberRules.byCountry[country]?.accepts(normalised) == true
        if (!recognised) return LABEL_TIN
        val digits = normalised.length
        return if (country == Country.FR) {
            if (digits == FR_SIREN_DIGITS) LABEL_SIREN else LABEL_N_FISCAL
        } else if (country == Country.BE) {
            if (digits == BE_KBO_DIGITS) LABEL_KBO else LABEL_RRN
        } else if (country == Country.NL) {
            LABEL_RSIN
        } else if (country == Country.LU) {
            LABEL_MATRICULE
        } else {
            LABEL_TIN
        }
    }

    /**
     * True when the tax number carries no information beyond the VAT ID of the same country, so the
     * printed row can be dropped: the number equals the VAT ID without its two-letter prefix. The
     * Dutch VAT form `NL<9 digits>B<2 digits>` is compared by its 9-digit part (RSIN), and a Belgian
     * VAT ID written with 9 digits equals the 10-digit number with its leading 0.
     */
    fun duplicatesVatId(country: Country, taxNumber: String, vatId: String): Boolean {
        val number = normalize(taxNumber)
        val vat = normalize(vatId)
        val prefix = if (country == Country.GR) "EL" else country.alpha2
        if (number.isEmpty() || !vat.startsWith(prefix) || vat.length <= VAT_PREFIX_LENGTH) return false
        val rest = vat.substring(VAT_PREFIX_LENGTH)
        val nlCore = nlVat.matchEntire(rest)?.groupValues?.get(1)
        return number == rest ||
            (country == Country.NL && number == nlCore) ||
            (country == Country.BE && rest.length == BE_SHORT_DIGITS && number == "0$rest")
    }
}
