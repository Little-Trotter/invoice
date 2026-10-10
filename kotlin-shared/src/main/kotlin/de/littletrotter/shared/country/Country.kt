package de.littletrotter.shared.country

/**
 * Country enum with ISO 3166-1 alpha-2 code and numeric value.
 * Only a practical subset is included (EU + common extras). Extend as needed.
 */
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import java.time.LocalDate

enum class Country(val alpha2: String, val numeric: Int) {
    AT("AT", 40),
    BE("BE", 56),
    BG("BG", 100),
    CH("CH", 756),
    CY("CY", 196),
    CZ("CZ", 203),
    DE("DE", 276),
    DK("DK", 208),
    EE("EE", 233),
    ES("ES", 724),
    FI("FI", 246),
    FR("FR", 250),
    GB("GB", 826),
    GR("GR", 300),
    HR("HR", 191),
    HU("HU", 348),
    IE("IE", 372),
    IN("IN", 356),
    IT("IT", 380),
    LT("LT", 440),
    LU("LU", 442),
    LV("LV", 428),
    MT("MT", 470),
    NL("NL", 528),
    NO("NO", 578),
    NZ("NZ", 554),
    PL("PL", 616),
    PT("PT", 620),
    RO("RO", 642),
    SE("SE", 752),
    SI("SI", 705),
    SK("SK", 703),
    US("US", 840),
    CA("CA", 124),
    AU("AU", 36);

    @JsonValue
    fun toJson(): String = alpha2

    override fun toString(): String = alpha2

    /**
     * R1 — "war dieses Land am Leistungsdatum EU-Mitglied": a plain boolean/enum flag can't
     * answer that (GB is in this enum and the reason a static Set<Country> isn't enough — it
     * left the EU VAT area at the end of the Brexit transition period). Modeled as a join
     * date plus an optional exit date, not as a fixed membership set.
     */
    fun isEuMemberOn(date: LocalDate): Boolean {
        val joined = EU_MEMBER_SINCE[this] ?: return false
        if (date.isBefore(joined)) return false
        val left = EU_MEMBER_UNTIL[this] ?: return true
        return !date.isAfter(left)
    }

    companion object {
        fun fromNumeric(n: Int?): Country? = n?.let { entries.find { v -> v.numeric == it } }
        fun fromAlpha2(code: String?): Country? = code?.let { entries.find { v -> v.alpha2.equals(code, ignoreCase = true) } }

        /**
         * Every country whose [alpha2] contains [text], case-insensitively — the resolver behind
         * free-text search on a country column.
         *
         * A `country` column holds this enum as an `Integer` (see `CountryAttributeConverter`), so
         * `lower(country) LIKE :filter` is not merely imprecise, it is rejected outright: Hibernate
         * refuses `lower()` on an integer argument at query-parse time and every non-blank search
         * term throws. Resolving the text to enum values here and filtering with `country IN (...)`
         * keeps the search working and stays inside the enum-as-smallint persistence rule.
         *
         * Returns an empty list when nothing matches; callers must then produce no rows rather than
         * building an empty `IN ()`, which is not valid JPQL.
         */
        fun matching(text: String?): List<Country> {
            val needle = text?.trim().orEmpty()
            if (needle.isEmpty()) return emptyList()
            return entries.filter { it.alpha2.contains(needle, ignoreCase = true) }
        }

        @JvmStatic
        @JsonCreator
        fun fromJson(code: String?): Country? = fromAlpha2(code)

        /**
         * A VAT id's issuing country, read off its first two (uppercase-trimmed)
         * characters. Extracted here, in `domain/masterdata` (not `domain/compliance`), so both
         * `FacturXEligibility`'s fixed FR/LU/BE scope test and the per-`PartyIdentifierSpec`
         * country matching (`PartyExtensionsSection.applyCountryContext`) can share one parse
         * instead of growing a second copy. `FacturXEligibility.vatCountryPrefix` predates this
         * extraction and stays private there — this file's ownership does not include the
         * `domain.compliance` package, so that existing call site is left as-is; new call sites
         * use this one. A blank, too-short, or otherwise unparseable VAT id returns `null`, never
         * throws — a half-filled draft must remain evaluable.
         */
        fun fromVatIdPrefix(vatId: String?): Country? {
            val normalized = vatId?.trim()?.uppercase() ?: return null
            if (normalized.length < 2) return null
            val prefix = normalized.substring(0, 2)
            return if (prefix == "EL") GR else fromAlpha2(prefix)
        }

        // R1 — EU accession dates (well-documented historical facts). Countries absent from
        // this map were never EU members (CH, US, CA, AU, NZ, IN, NO — NO is EEA, not EU).
        private val EU_MEMBER_SINCE: Map<Country, LocalDate> = mapOf(
            BE to LocalDate.of(1958, 1, 1), DE to LocalDate.of(1958, 1, 1),
            FR to LocalDate.of(1958, 1, 1), IT to LocalDate.of(1958, 1, 1),
            LU to LocalDate.of(1958, 1, 1), NL to LocalDate.of(1958, 1, 1),
            DK to LocalDate.of(1973, 1, 1), IE to LocalDate.of(1973, 1, 1),
            GB to LocalDate.of(1973, 1, 1),
            GR to LocalDate.of(1981, 1, 1),
            ES to LocalDate.of(1986, 1, 1), PT to LocalDate.of(1986, 1, 1),
            AT to LocalDate.of(1995, 1, 1), FI to LocalDate.of(1995, 1, 1), SE to LocalDate.of(1995, 1, 1),
            CY to LocalDate.of(2004, 5, 1), CZ to LocalDate.of(2004, 5, 1), EE to LocalDate.of(2004, 5, 1),
            HU to LocalDate.of(2004, 5, 1), LT to LocalDate.of(2004, 5, 1), LV to LocalDate.of(2004, 5, 1),
            MT to LocalDate.of(2004, 5, 1), PL to LocalDate.of(2004, 5, 1), SK to LocalDate.of(2004, 5, 1),
            SI to LocalDate.of(2004, 5, 1),
            BG to LocalDate.of(2007, 1, 1), RO to LocalDate.of(2007, 1, 1),
            HR to LocalDate.of(2013, 7, 1),
        )

        // GB: the EU VAT area's own end date (end of the Brexit transition period) is used
        // here, not the earlier political withdrawal date (2020-01-31) — for a *tax* rule
        // this is the legally relevant cutoff, and using the political date would wrongly
        // treat two more months of genuinely intra-EU GB transactions as third-country.
        private val EU_MEMBER_UNTIL: Map<Country, LocalDate> = mapOf(
            GB to LocalDate.of(2020, 12, 31),
        )

        /**
         * PO decision (2026-08) — every *current* EU member state except Germany. This supersedes
         * the original MVP curation (`FR, BE, NL, LU`, a narrow fachlich freigegebene
         * subset); the PO has since asked for the full current EU country set instead. `DE` stays
         * excluded on purpose: this set is for the seller's *additional, non-domestic*
         * registrations — the seller's own German VAT id is the sender profile's VAT id and was never
         * part of this set.
         * `GB` is excluded too, but not by an extra check here: it fell out of EU membership at
         * the end of the Brexit transition period, so [isEuMemberOn] already returns `false` for
         * it as of any current date. Derived off [EU_MEMBER_SINCE]/[EU_MEMBER_UNTIL] rather than
         * listed by hand so a future EU accession (or, hypothetically, another departure) is
         * picked up here automatically once those maps are updated.
         *
         * A domain object may not read the clock itself (TimeSourceArchitectureTest); the caller
         * (a module holding a [de.littletrotter.shared.time.TimeSource]) passes its
         * `today()` explicitly.
         */
        fun supportedSellerVatRegistrationCountries(today: LocalDate): Set<Country> =
            entries.filter { it != DE && it.isEuMemberOn(today) }.toSet()

        /**
         * PO decision (2026-08-25, evening): countries whose business language is German for the
         * purposes of the invoice-format DEFAULT — a cross-border invoice touching one of these
         * (reverse-charge buyer, or an active seller VAT registration there) keeps GERMAN as the
         * preselected document format; every other country preselects INTERNATIONAL. One-time default, never
         * an override of a manual choice. CH is listed although it is currently unreachable on
         * both paths (non-EU: the RC resolver blocks it as Drittland, and it is not in
         * [SUPPORTED_SELLER_VAT_REGISTRATION_COUNTRIES]) — encoded anyway so the PO's rule holds
         * verbatim if either mechanism ever widens.
         */
        val GERMAN_BUSINESS_LANGUAGE_COUNTRIES: Set<Country> = setOf(DE, AT, CH)

        /**
         * R1 — countries directly released for the `EU_B2B_RC_SERVICE` reverse-charge rule
         * in this project's scope (general services, no OSS/local registration). A country
         * being an EU member is necessary but not sufficient — membership itself is still
         * checked separately and per-date by [de.littletrotter.shared.tax.TaxRuleResolver] via [isEuMemberOn], this set
         * only gates which countries are *released* at all.
         *
         * PO decision (2026-08-25) — extended to every current EU member state except Germany,
         * the same "all 27 minus DE" decision already made for
         * [SUPPORTED_SELLER_VAT_REGISTRATION_COUNTRIES] (see that field's KDoc for the
         * precedent and the GB/Brexit reasoning, which applies identically here). This
         * supersedes the original curated MVP list (`AT, BE, CZ, DK, FR, LU, NL, PL`) that was
         * blocking otherwise-valid reverse-charge invoices — e.g. to Italy — with
         * `TAX.UNSUPPORTED_TAX_CASE`. `DE` is excluded here too, even though a `DE` buyer would
         * never reach this branch (handled earlier as the domestic case) — kept identical to
         * the seller-registration set so the two don't silently diverge later. Reusing that set
         * directly rather than recomputing the same filter a second time.
         */
        fun supportedEuB2bTargets(today: LocalDate): Set<Country> =
            supportedSellerVatRegistrationCountries(today)
    }
}
