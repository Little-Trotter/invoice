package de.littletrotter.shared.tax

import java.time.OffsetDateTime

// ── Confirmation / staleness (Integration §2/§3) ────────────────────────────

/** R5 — a user's confirmation of a resolver proposal, kept separate from the proposal itself
 *  (Integration §2: "Benutzerbestätigung getrennt vom Vorschlag speichern"). Not a persisted
 *  entity — R5 has no migration; a caller (R6/R7) owns where/whether this gets stored. */
data class TaxRuleConfirmation(
    val ruleCode: String,
    val confirmedContext: TaxRuleResolutionRequest,
    val confirmedAt: OffsetDateTime,
)

/** Integration §3 — a change to buyer country, customer type, service date or either VAT id
 *  invalidates a previously confirmed proposal. Deliberately narrower than full equality: two
 *  fields the resolver output doesn't depend on (e.g. [ExplicitExemption]'s free text) must not
 *  cause spurious staleness. */
fun TaxRuleConfirmation.isStaleFor(current: TaxRuleResolutionRequest): Boolean {
    val c = confirmedContext
    return c.buyerCountry != current.buyerCountry ||
        c.customerType != current.customerType ||
        c.serviceDate != current.serviceDate ||
        c.buyerVatId != current.buyerVatId ||
        // A confirmation made while the invoice billed under a different (or no)
        // seller VAT registration must not silently stay valid after that selection changes,
        // even though R5's own decision table never reads this field.
        c.sellerVatRegistrationCountry != current.sellerVatRegistrationCountry
}
