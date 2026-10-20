package de.littletrotter.domain.value

import de.littletrotter.shared.country.Country

/**
 * One country's tax number rule: the accepted structures over the normalised number plus a short
 * German description of the expected format for warning texts.
 */
internal class TaxNumberRule(val formatHint: String, structure: String) {
    private val pattern = Regex(structure)

    fun accepts(normalised: String): Boolean = pattern.matches(normalised)
}

/**
 * Registry of the per-country structure rules for the countries in which a seller may hold a VAT
 * registration (all EU member states except Germany). The EU Commission's country sheets define the
 * structure of the number for natural persons and name no check digit algorithm, so only structures
 * are checked and no check digit. Structures of company numbers (SIREN, KBO, Y-tunnus and the like)
 * are not part of the sheets; they are the publicly documented national register formats and are
 * accepted in addition because sole proprietors and companies both register.
 */
internal object ForeignTaxNumberRules {

    private const val D8 = "\\d{8}"
    private const val D9 = "\\d{9}"
    private const val D10 = "\\d{10}"
    private const val D11 = "\\d{11}"
    private const val ES_PERSON = "$D8[A-Z]|[KLMXYZ]\\d{7}[A-Z]"
    private const val ES_COMPANY = "[ABCDEFGHJNPQRSUVW]\\d{7}[0-9A-J]"
    private const val IT_PERSON = "[A-Z]{6}[0-9LMNP-V]{2}[A-Z][0-9LMNP-V]{2}[A-Z][0-9LMNP-V]{3}[A-Z]"

    val byCountry: Map<Country, TaxNumberRule> = mapOf(
        Country.AT to TaxNumberRule("9 Ziffern (Finanzamt und Nummer)", "$D9"),
        Country.BE to TaxNumberRule("11 Ziffern (Nationalnummer) oder 10 Ziffern (Unternehmensnummer)", "$D11|[01]$D9"),
        Country.BG to TaxNumberRule("10 Ziffern (EGN/LNC), 9 oder 13 Ziffern (EIK/BULSTAT)", "$D10|$D9|\\d{13}"),
        Country.CY to TaxNumberRule("8 Ziffern und 1 Buchstabe", "$D8[A-Z]"),
        Country.CZ to TaxNumberRule("9 bis 10 Ziffern (Geburtsnummer) oder 8 Ziffern (IČO)", "\\d{8,10}"),
        Country.DK to TaxNumberRule("10 Ziffern (CPR) oder 8 Ziffern (CVR)", "$D10|$D8"),
        Country.EE to TaxNumberRule("11 Ziffern (Personenkennzahl) oder 8 Ziffern (Registrierungscode)", "$D11|$D8"),
        Country.ES to TaxNumberRule(
            "8 Ziffern und Buchstabe; K/L/M/X/Y/Z, 7 Ziffern, Buchstabe; Unternehmen: Buchstabe, 7 Ziffern, Kontrollzeichen",
            "$ES_PERSON|$ES_COMPANY",
        ),
        Country.FI to TaxNumberRule(
            "Personenkennung (z. B. 131052-308T) oder 8 Ziffern (Y-tunnus)",
            "\\d{6}[+A]?\\d{3}[0-9A-Z]|$D8",
        ),
        Country.FR to TaxNumberRule("13 Ziffern (Steuernummer, beginnt mit 0 bis 3) oder 9 Ziffern (SIREN)", "[0-3]\\d{12}|$D9"),
        Country.GR to TaxNumberRule("9 Ziffern (AFM)", "$D9"),
        Country.HR to TaxNumberRule("11 Ziffern (OIB)", "$D11"),
        Country.HU to TaxNumberRule("10 Ziffern (Steuerkennzeichen) oder 11 Ziffern (Steuernummer)", "$D10|$D8[1-5]\\d{2}"),
        Country.IE to TaxNumberRule("7 Ziffern und 1 bis 2 Buchstaben (PPS-Nummer/Steuernummer)", "\\d{7}[A-Z]{1,2}"),
        Country.IT to TaxNumberRule("16 Zeichen (Codice fiscale) oder 11 Ziffern (Partita IVA)", "$IT_PERSON|$D11"),
        Country.LT to TaxNumberRule("11 Ziffern (Personenkennzahl) oder 9 Ziffern (Unternehmenscode)", "$D11|$D9"),
        Country.LU to TaxNumberRule("13 Ziffern (Matricule) oder 11 Ziffern (Unternehmen)", "\\d{13}|$D11"),
        Country.LV to TaxNumberRule("11 Ziffern", "$D11"),
        Country.MT to TaxNumberRule("3 bis 7 Ziffern und Buchstabe (M, G, A, P, L, H, B, Z) oder 9 Ziffern", "\\d{3,7}[MGAPLHBZ]|$D9"),
        Country.NL to TaxNumberRule("9 Ziffern (BSN/RSIN)", "$D9"),
        Country.PL to TaxNumberRule("10 Ziffern (NIP) oder 11 Ziffern (PESEL)", "$D10|$D11"),
        Country.PT to TaxNumberRule("9 Ziffern (NIF)", "$D9"),
        Country.RO to TaxNumberRule("13 Ziffern (CNP) oder 2 bis 10 Ziffern (CUI/CIF)", "\\d{13}|[1-9]\\d{1,9}"),
        Country.SE to TaxNumberRule("10 Ziffern (Personen-/Organisationsnummer) oder 12 Ziffern mit Jahrhundert", "$D10|(18|19|20)$D10"),
        Country.SI to TaxNumberRule("8 Ziffern (Steuernummer)", "$D8"),
        Country.SK to TaxNumberRule("9 bis 10 Ziffern (Geburtsnummer/DIČ)", "\\d{9,10}"),
    )
}
