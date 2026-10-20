package de.littletrotter.shared

/**
 * The one decision whether a selected foreign VAT registration applies to an invoice: only when the
 * recipient sits in the registration's country. Otherwise the supply is not made under that
 * registration, so neither its VAT id nor its tax number may appear (PDF, XML BT-31, frozen
 * snapshot all ask here). Codes are trimmed and compared case-insensitively; a recipient without a
 * country (the DTO leaves Germany `null`) never matches, as Germany is never an additional
 * registration.
 *
 * @return the trimmed registration country when effective, otherwise `null`
 */
fun effectiveSellerRegistrationCountry(registrationCountry: String?, recipientCountry: String?): String? {
    val registration = registrationCountry?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val recipient = recipientCountry?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return registration.takeIf { it.equals(recipient, ignoreCase = true) }
}

/**
 * Core resolution logic: find the VAT registration (if any) for the given country code.
 * Used by both [resolveActiveSellerVatId] and `buildContext` to ensure
 * consistent matching with proper trimming and case-insensitive comparison.
 *
 * Trims the country code before matching to prevent drift due to whitespace in the input data.
 *
 * @param senderProfile The seller's profile containing additional VAT registrations
 * @param registrationCountry The country code to match (will be trimmed before matching)
 * @return The matching registration, or null if none found or if country is blank after trimming
 */
fun findSellerVatRegistrationByCountry(
    senderProfile: PartyDto?,
    registrationCountry: String?,
): VatRegistrationDto? {
    val trimmedCountry = registrationCountry?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return senderProfile?.additionalVatRegistrations
        ?.firstOrNull { it.country.equals(trimmedCountry, ignoreCase = true) }
}

/**
 * The seller's VAT-ID (BT-31) for the e-invoice wire format, corrected for
 * an invoice billed under one of the seller's foreign VAT registrations
 * ([InvoiceMetaDto.sellerVatRegistrationCountry]).
 *
 * The live defect this closes: a 17% invoice billed under the seller's LU
 * registration, but the e-invoice export ([de.littletrotter.xpdf.impl.mustang
 * .MustangExportModelMapper]) carried the seller's German home `vatId` unconditionally — the XML asserted a German USt-ID for
 * a supply that is only legitimately taxed at 17% under the Luxembourg TVA number. The PDF display
 * side now uses the same core resolution logic ([findSellerVatRegistrationByCountry]),
 * ensuring consistent matching between XML exports and PDF footer.
 *
 * BT-31 is 0..1 in EN 16931 — one seller VAT-ID, not two. When the seller's active registration
 * for this invoice resolves to a foreign entry, its number **replaces** the home `vatId` on the
 * wire rather than being emitted alongside it: the invoice is legally issued under that foreign
 * registration, so declaring the German number too would misstate which registration legitimizes
 * the invoice's tax treatment.
 *
 * Buyer/recipient VAT-IDs are entirely unaffected — this only ever resolves the *seller's* BT-31.
 *
 * A registration the recipient's country does not match is not effective
 * ([effectiveSellerRegistrationCountry]) and falls back to the home `vatId` as well.
 *
 * No matching registration (country set but not actually captured on [senderProfile], or no
 * country set at all) is not an error: this defensively falls back to the unconditional home
 * `vatId`.
 */
fun resolveActiveSellerVatId(senderProfile: PartyDto?, meta: InvoiceMetaDto, recipient: PartyDto?): String? {
    val homeVatId = senderProfile?.vatId?.trim()?.takeIf { it.isNotBlank() }
    val registrationVatId = findSellerVatRegistrationByCountry(
        senderProfile,
        effectiveSellerRegistrationCountry(meta.sellerVatRegistrationCountry, recipient?.country),
    )
        ?.vatId
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    return registrationVatId ?: homeVatId
}
