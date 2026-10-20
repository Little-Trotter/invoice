package de.littletrotter.domain.masterdata

/**
 * The resolved invoice-note text for [language] (a document language code, e.g. `"de"`/`"en"`);
 * see [de.littletrotter.domain.reference.LocalizedTextGroup.resolvedFor].
 */
fun SenderProfile.invoiceNoteFor(language: String): String? = invoiceNote?.resolvedFor(language)

/**
 * The sender's own consumer-notice text for exactly [language], or `null` if none is
 * maintained for it; no fallback to another language, so the caller can use that language's
 * default wording instead.
 */
fun SenderProfile.consumerNoteFor(language: String): String? = consumerNote?.exactFor(language)

/**
 * Whether the invoice (PDF footer and e-invoice data) should show this seller's
 * commercial-register entry (Handelsregisternummer/Registergericht).
 *
 * A sole proprietor ([LegalForm.SOLE_PROPRIETOR]) who is registered with a Handwerkskammer
 * ([SenderProfile.craftsChamberRegistered]) is not entered in the Handelsregister at all, so
 * printing [SenderProfile.registerCourt]/[SenderProfile.commercialRegisterNumber] would be wrong
 * data (a register entry that does not exist). Every other combination keeps showing the
 * register data: no legal form recorded, another legal form, or a sole proprietor without a
 * Handwerkskammer registration (who may well be a registered merchant).
 */
fun SenderProfile.showsCommercialRegister(): Boolean =
    !(legalForm == LegalForm.SOLE_PROPRIETOR && craftsChamberRegistered)
