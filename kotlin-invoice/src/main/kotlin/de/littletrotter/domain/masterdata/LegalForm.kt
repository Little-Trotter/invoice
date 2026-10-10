package de.littletrotter.domain.masterdata

/**
 * A seller's legal form ("Rechtsform"), captured on the "Firmierung" tab of
 * [SenderProfile]. Nullable there: `null` means "keine Angabe", the same behavior as before this
 * field existed.
 *
 * [labelKey] is the German i18n key shown in the UI's Rechtsform combobox (see
 * [de.littletrotter.ui.view.masterdata.SenderProfileView]); this enum's constants stay English
 * per the house code-language rule.
 *
 * Deliberately no exhaustive German legal-form catalog — only the forms the PO named, because
 * [SenderProfile.showsCommercialRegister] only needs to distinguish [SOLE_PROPRIETOR]
 * from every registered form. Persisted by ordinal
 * ([de.littletrotter.domain.masterdata.converters.LegalFormConverter]) — append new constants at
 * the end only, never reorder.
 */
enum class LegalForm(val labelKey: String, val group: LegalFormGroup) {
    SOLE_PROPRIETOR("senderProfile.legalForm.soleProprietor", LegalFormGroup.EINZEL),
    GMBH("senderProfile.legalForm.gmbh", LegalFormGroup.KAPITALGESELLSCHAFT),
    GMBH_CO_KG("senderProfile.legalForm.gmbhCoKg", LegalFormGroup.PERSONENGESELLSCHAFT),
    KG("senderProfile.legalForm.kg", LegalFormGroup.PERSONENGESELLSCHAFT),
    OHG("senderProfile.legalForm.ohg", LegalFormGroup.PERSONENGESELLSCHAFT),

    // Appended (ordinal persistence); registered forms all show their register
    // entry via SenderProfile.showsCommercialRegister (eG: Genossenschaftsregister, e.V.:
    // Vereinsregister — the register fields are free-text, so the label fits either).
    EG("senderProfile.legalForm.eg", LegalFormGroup.KOERPERSCHAFT),
    AG("senderProfile.legalForm.ag", LegalFormGroup.KAPITALGESELLSCHAFT),
    SE("senderProfile.legalForm.se", LegalFormGroup.KAPITALGESELLSCHAFT),
    VEREIN("senderProfile.legalForm.verein", LegalFormGroup.KOERPERSCHAFT),
    GBR("senderProfile.legalForm.gbr", LegalFormGroup.PERSONENGESELLSCHAFT),
    ;

    companion object {
        /** The Rechtsform combobox presents the forms grouped
         *  (Einzelunternehmer, dann Personen-, dann Kapitalgesellschaften, dann
         *  Körperschaften), each group in its legally customary order. Display order
         *  lives HERE, never in the ordinal
         *  (persistence, [de.littletrotter.domain.masterdata.converters.LegalFormConverter]). */
        val UI_ORDER: List<LegalForm> = listOf(
            SOLE_PROPRIETOR,
            GBR, OHG, KG, GMBH_CO_KG,
            GMBH, AG, SE,
            EG, VEREIN,
        )
    }
}

/** Grouping for [LegalForm.UI_ORDER] and future group-aware rendering. */
enum class LegalFormGroup {
    EINZEL,
    PERSONENGESELLSCHAFT,
    KAPITALGESELLSCHAFT,
    KOERPERSCHAFT,
}
