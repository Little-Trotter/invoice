package de.littletrotter.domain.invoice

/**
 * F4/R8 — the export profile id `XmlExportServiceImpl.resolveProfile()` (kotlin-xpdf) would
 * choose for a seller/buyer VAT-id pair. A single implementation inside kotlin-invoice so F4's
 * finalization gate ([de.littletrotter.application.invoice.InvoiceLifecycleService
 * .resolveValidationProfileId]) and R8's producer ([de.littletrotter.application.invoice
 * .DocumentJobPublisher]) can never disagree about which profile an invoice would export as —
 * before R8 each duplicated this decision independently. kotlin-xpdf still needs its own copy
 * (no dependency edge from that module onto this one); see the cross-reference comment on
 * `XmlExportServiceImpl.resolveProfile()`. Fixing the *selection logic* itself (it's fachlich
 * fragwürdig, hangs on vatId presence rather than a real profile decision) stays TAX-06's job —
 * this function's only purpose is to be the one place that logic lives inside kotlin-invoice.
 *
 * Escalation to `EXTENDED` (PO decision "profile escalation, only when Skonto"): structured
 * early-payment discount terms only exist in the ZUGFeRD/Factur-X EXTENDED profile — EN16931 and
 * XRechnung have no field for them, so an invoice with a cash discount that would otherwise export
 * as EN16931 could be escalated so the discount can travel as data instead of being lost. XRechnung
 * is never escalated: EXTENDED is not XRechnung-conformant, and a VAT id on either side already
 * forces that legal requirement to win.
 *
 * PO decision 2026-09-28 (revises the escalation above): the KoSIT XRechnung validator cannot
 * validate the EXTENDED profile at all, so an *automatic* escalation left every such Skonto
 * invoice unvalidatable. The escalation is therefore off by default — `EN16931` with the discount
 * only as text in the payment terms — and only fires when the admin has explicitly opted in via
 * `FeatureFlag.EXTENDED_PROFILE`; see [Invoice.requiresExtendedProfile]'s own KDoc.
 */
fun resolveExportProfileId(sellerVatId: String?, buyerVatId: String?, extendedFeatures: Boolean = false): String {
    val hasVatId = !buyerVatId.isNullOrBlank() || !sellerVatId.isNullOrBlank()
    return when {
        hasVatId -> "XRechnung"
        extendedFeatures -> "EXTENDED"
        else -> "EN16931"
    }
}

/**
 * Whether [resolveExportProfileId] must escalate this invoice's export profile to `EXTENDED`.
 * `extendedProfileEnabled` is the admin opt-in (`FeatureFlag.EXTENDED_PROFILE`, off by default —
 * PO decision 2026-09-28: the KoSIT XRechnung validator cannot validate EXTENDED, so an
 * unconditional escalation left every Skonto invoice unvalidatable; escalating is now the admin's
 * explicit choice, not the product default).
 */
fun Invoice.requiresExtendedProfile(extendedProfileEnabled: Boolean): Boolean =
    extendedProfileEnabled && cashDiscount() != null
