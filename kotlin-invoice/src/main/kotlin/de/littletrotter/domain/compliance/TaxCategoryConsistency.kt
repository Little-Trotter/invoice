package de.littletrotter.domain.compliance

import de.littletrotter.shared.tax.ServiceType
import de.littletrotter.shared.tax.TaxRuleResolution
import de.littletrotter.shared.tax.TaxRuleResolutionRequest
import de.littletrotter.shared.tax.TaxRuleResolver
import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.tax.VatCategory
import java.time.LocalDate

/**
 * Builds R5's resolver input from this invoice's own party data. R5 deliberately keeps
 * [TaxRuleResolutionRequest] free of any [Invoice] dependency ("a caller (R6/R7) builds this from
 * the P1 fields") — this is that caller on the domain side, next to the lifecycle service that
 * needs it, mirroring what `TaxRuleUiSupport.buildTaxRuleResolutionRequest` does for the UI.
 *
 * `null` when the recipient's master data does not carry enough to decide (no recipient, no
 * customer type, no billing country) — no claim is then made either way.
 *
 * Also `null` while a foreign seller VAT registration is active
 * ([Invoice.sellerVatRegistrationCountry]): R5's recipient-context derivation does not
 * apply on that path at all — every article-bound item pulls its tax from the article's country
 * variant, and judging those items against R5's DE-seller decision table produced false
 * `TAX.CATEGORY_MISMATCH` errors (live: an invoice under an active LU registration with correct
 * S/17% LU-variant items, while the resolver proposed AE from the LU recipient). This path's
 * own correctness gate is `validateItemArticleTaxVariants`, not R5. Guarded here, in the one
 * builder every consumer shares, rather than per caller — [reverseChargeEligibility] used to
 * carry its own copy of this check and [TaxRules.evaluate] did not, which is exactly how the
 * false positive slipped through.
 */
fun Invoice.taxRuleResolutionRequestOrNull(
    serviceType: ServiceType = ServiceType.GENERAL_SERVICE,
): TaxRuleResolutionRequest? {
    if (sellerVatRegistrationCountry != null) return null
    val buyer = recipient ?: return null
    val customerType = buyer.customerType ?: return null
    val buyerCountry = Country.fromVatIdPrefix(buyer.vatId?.value) ?: return null
    return TaxRuleResolutionRequest(
        serviceType = serviceType,
        // date invariants (PO 2026-09-02) — invoiceDate is mandatory from creation, so the
        // fallback this used to need (`?: LocalDate.now()`) is unreachable now.
        serviceDate = invoiceDate,
        // Only a German seller is supported at all (R5 §1, README Zielbild).
        sellerCountry = Country.DE,
        customerType = customerType,
        buyerCountry = buyerCountry,
        sellerVatId = (selectedSeller ?: senderProfile)?.vatId?.value,
        buyerVatId = buyer.vatId?.value,
        buyerVatIdVerificationStatus = buyer.vatIdVerificationStatus,
    )
}

/**
 * Whether [Invoice.recipient] currently satisfies Reverse Charge's preconditions (EU
 * non-DE country, business customer, `VERIFIED` VAT-id proof) plus the invoice's own precondition
 * that no seller VAT registration is active ([Invoice.sellerVatRegistrationCountry] — this
 * seller-country path is independent of R5 and has no Reverse-Charge notion of its own).
 *
 * Reuses [TaxRuleResolver]'s decision table rather than re-deriving the same rule a second time:
 * eligible means the resolver itself would propose [VatCategory.REVERSE_CHARGE] for this invoice.
 * The header toggle (`InvoiceDetailViewOverview`'s entry pane,
 * via its own UI-side counterpart reading live master data) and the finalization gate
 * ([assertReverseChargeRecipientEligibility]) both read this the same way — `null` means
 * ineligible, non-null carries the resolver's own proposed rule code to apply.
 */
fun Invoice.reverseChargeEligibility(today: LocalDate): TaxRuleResolution.Proposed? {
    // The seller-VAT-registration precondition lives in taxRuleResolutionRequestOrNull itself
    // now (see its KDoc) — an active registration yields no request, hence null here.
    val request = taxRuleResolutionRequestOrNull() ?: return null
    return (TaxRuleResolver.resolve(request, today) as? TaxRuleResolution.Proposed)
        ?.takeIf { it.category == VatCategory.REVERSE_CHARGE }
}

/** The German reason [reverseChargeEligibility] returned `null`, for the header
 *  toggle's disabled-tooltip and the [assertReverseChargeRecipientEligibility] gate message. */
fun Invoice.reverseChargeIneligibilityReason(today: LocalDate): String {
    if (sellerVatRegistrationCountry != null) {
        return "Reverse Charge ist nicht verfügbar, solange eine ausländische Verkäufer-USt-IdNr.-Registrierung aktiv ist."
    }
    val request = taxRuleResolutionRequestOrNull()
        ?: return "Empfängerdaten sind unvollständig (Land, Kundentyp oder USt-IdNr. fehlen)."
    return when (val resolution = TaxRuleResolver.resolve(request, today)) {
        is TaxRuleResolution.Blocked -> resolution.detail
        is TaxRuleResolution.Proposed ->
            "Empfänger erfüllt die Voraussetzungen für Reverse Charge nicht (aktuell vorgeschlagene Kategorie: ${resolution.category.code})."
    }
}

/**
 * Blocks `→ ISSUED` when the header decision is ON but the recipient no longer
 * qualifies (e.g. master data changed after the toggle was set). A no-op while the decision is
 * OFF: an ineligible recipient never mattered before this decision existed, and still doesn't
 * unless the user actually asked for Reverse Charge. See [de.littletrotter.domain.invoice
 * .validateReverseChargeItemConsistency] for the companion checks (decision vs. item categories)
 * this lives next to in the gate.
 */
fun Invoice.assertReverseChargeRecipientEligibility(today: LocalDate) {
    if (!reverseCharge) return
    if (reverseChargeEligibility(today) != null) return
    throw InvoiceValidationException(
        InvoiceValidationError.REVERSE_CHARGE_RECIPIENT_NOT_ELIGIBLE,
        listOf(reverseChargeIneligibilityReason(today)),
    )
}
