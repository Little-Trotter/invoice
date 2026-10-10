package de.littletrotter.domain.invoice

import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.ReverseChargePreference

/**
 * The pure decision behind automatically adopting the seller's foreign VAT
 * registration: when the seller already holds a VAT registration for the recipient's own
 * country (resolved from the recipient's address the same way as
 * [de.littletrotter.domain.invoice.Invoice.documentLanguage] — billing address, falling back to
 * delivery address), the invoice (and every article-bound position on it) should be taxed under
 * that registration without a manual `sellerVatRegistrationCountry` selection. Example persona: a
 * DE consultant with an LU VAT registration works for a Luxembourg customer — the invoice should
 * carry the LU registration automatically.
 *
 * Both `InvoiceLifecycleService.create` (R-1) and `.updateHeader` (R-2) call this single unit —
 * it is the dependency-free extraction the 100%-coverage gate in `kotlin-invoice/pom.xml`
 * targets (§5), so the decision itself is provable in isolation, without a database or a managed
 * [de.littletrotter.domain.invoice.Invoice].
 *
 * Returns [recipientCountry] iff it is non-null, is not [Country.DE] (Germany is never an
 * "additional" registration — see [de.littletrotter.domain.masterdata.SellerVatRegistration] and
 * [Country.SUPPORTED_SELLER_VAT_REGISTRATION_COUNTRIES]'s own KDoc), and contained in
 * [sellerRegistrationCountries]; `null` in every other case. A `null` result is never an error —
 * it simply means the existing manual-selection / German-default path
 * (`InvoiceLifecycleService.updateHeader`'s `command.sellerVatRegistrationCountry`) keeps
 * applying unchanged.
 */
fun deriveSellerVatRegistrationCountry(
    recipientCountry: Country?,
    sellerRegistrationCountries: Set<Country>,
): Country? {
    if (recipientCountry == null) return null
    if (recipientCountry == Country.DE) return null
    if (recipientCountry !in sellerRegistrationCountries) return null
    return recipientCountry
}

/** An explicit Reverse-Charge yes/no answer is required only for two different, known VAT-ID
 * countries when the seller has no registration in the buyer's VAT country. Address countries
 * deliberately do not participate in this decision. */
fun requiresReverseChargeDecision(
    sellerVatId: String?,
    recipientVatId: String?,
    sellerRegistrationCountries: Set<Country>,
): Boolean {
    val sellerCountry = Country.fromVatIdPrefix(sellerVatId) ?: return false
    val recipientCountry = Country.fromVatIdPrefix(recipientVatId) ?: return false
    return sellerCountry != recipientCountry && recipientCountry !in sellerRegistrationCountries
}

/** What [de.littletrotter.ui.view.invoice.InvoiceCreateDialog] should actually show/do for
 *  the Reverse-Charge question, given the recipient's standing [ReverseChargePreference]. Wraps
 *  [requiresReverseChargeDecision] and [deriveSellerVatRegistrationCountry] rather than
 *  re-deriving the country-mismatch check — those two stay the single source of truth for it. */
sealed class ReverseChargeCreationDecision {
    /** No dialog; `InvoiceLifecycleService.create` receives this value directly. */
    data class Resolved(val reverseChargeDecision: Boolean) : ReverseChargeCreationDecision()

    /** Dialog shown. allowYes is false when a matching seller registration makes "Yes"
     *  guaranteed-ineligible — the UI must not offer a choice that always errors. */
    data class Ask(val allowYes: Boolean) : ReverseChargeCreationDecision()
}

/**
 * [preference] `NEVER`/`ALWAYS` skip the creation dialog entirely, supplying the answer
 * up front exactly as if the office manager had clicked it. `ASK` (the default, and today's only
 * behaviour before this preference existed) keeps asking whenever the countries differ — but
 * unlike [requiresReverseChargeDecision] alone, `ASK` must still force the dialog open even when a
 * matching seller registration ([deriveSellerVatRegistrationCountry]) would otherwise resolve the
 * question silently: hasMatchingRegistration restores that "country differs" signal
 * independently of [requiresReverseChargeDecision]'s own early-out for it. In that forced-open
 * state Ask.allowYes is `false` — "Yes" is architecturally guaranteed to fail
 * `Invoice.reverseChargeEligibility()` whenever a matching registration is active, so the UI must
 * not offer it as a live choice.
 *
 * [sellerVatId]/[recipientVatId] stay VAT-ID-based for [requiresReverseChargeDecision] — that
 * function's own contract is unchanged by the Fix H seller-VAT-derivation correction.
 * hasMatchingRegistration is the one signal that comes from [deriveSellerVatRegistrationCountry]
 * instead, so it needs the recipient's own resolved [recipientCountry] (billing address, falling
 * back to delivery — the same resolution [de.littletrotter.application.invoice
 * .InvoiceLifecycleService.create] uses), not a VAT-ID guess.
 */
/** The recipient's own VAT id and resolved country — bundled so
 *  [resolveReverseChargeCreationDecision] takes one parameter for them instead of two
 *  (LongParameterList). */
data class RecipientVatInfo(
    val vatId: String?,
    val country: Country?,
)

fun resolveReverseChargeCreationDecision(
    preference: ReverseChargePreference,
    sellerVatId: String?,
    recipient: RecipientVatInfo,
    sellerRegistrationCountries: Set<Country>,
): ReverseChargeCreationDecision {
    val hasMatchingRegistration = deriveSellerVatRegistrationCountry(recipient.country, sellerRegistrationCountries) != null
    return when (preference) {
        ReverseChargePreference.NEVER -> ReverseChargeCreationDecision.Resolved(false)
        ReverseChargePreference.ALWAYS -> ReverseChargeCreationDecision.Resolved(true)
        ReverseChargePreference.ASK -> {
            val countriesDiffer = requiresReverseChargeDecision(sellerVatId, recipient.vatId, sellerRegistrationCountries) ||
                hasMatchingRegistration
            if (countriesDiffer) {
                ReverseChargeCreationDecision.Ask(allowYes = !hasMatchingRegistration)
            } else {
                ReverseChargeCreationDecision.Resolved(false)
            }
        }
    }
}
