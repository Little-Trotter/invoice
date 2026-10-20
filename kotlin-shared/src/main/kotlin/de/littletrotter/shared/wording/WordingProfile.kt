package de.littletrotter.shared.wording

/**
 * Per-installation vocabulary: [HANDWERK] (trade/craft) calls a sellable position
 * "Material", [HANDEL] (retail/trade) calls it "Artikel", [BERATUNG] (consulting/services) calls
 * it "Produkt(e)". A business trip is called "Dienstreise" regardless of profile (PO decision
 * 13.09.2026 — trip wording is no longer profile-dependent, only the product noun is). Scope is
 * per instance, never per [de.littletrotter.shared.PartyDto] — one company, one language.
 *
 * Persisted as [code], never [Enum.ordinal] (same convention as `InvoiceItemCategory`): the value
 * travels over the wire in `InvoiceDto.wordingProfile` and in kotlin-invoice's settings table,
 * both outside this module's own persistence layer, so a stable explicit code matters more than
 * declaration order. [BERATUNG] keeps the code the profile previously named `DIENSTLEISTUNG` held
 * (`1`) — only the Kotlin identifier changed, not the wire value.
 */
enum class WordingProfile(val code: Int) {
    HANDWERK(0),
    BERATUNG(1),
    HANDEL(2),
    ;

    companion object {
        /** The instance default before any env/DB override is known — HANDWERK, per the wording-profile spec. */
        val DEFAULT: WordingProfile = HANDWERK

        fun fromCode(code: Int): WordingProfile = fromCodeOrNull(code) ?: DEFAULT

        fun fromCodeOrNull(code: Int): WordingProfile? = entries.firstOrNull { it.code == code }
    }
}
