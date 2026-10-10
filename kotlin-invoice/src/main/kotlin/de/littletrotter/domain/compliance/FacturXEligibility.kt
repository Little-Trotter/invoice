package de.littletrotter.domain.compliance

import java.time.LocalDate
import de.littletrotter.domain.invoice.DocumentEngine
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.effectiveSender
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile

/**
 * the task doc, decision 11 — the **one** domain predicate for whether Factur-X is
 * offered at all, consumed by three otherwise-independent places: the export panel's engine
 * choice ([InvoiceExportPanel][de.littletrotter.ui.view.invoice.InvoiceExportPanel]), the
 * finalization gate ([FacturXEligibilityRuleSet] below), and the
 * `PartyExtensionsSection` field visibility. Before this existed each of those would have grown
 * its own copy of the country test, and the three copies would eventually disagree — the same
 * chokepoint reasoning [ExportProfile.resolveExportProfileId][de.littletrotter.domain.invoice
 * .resolveExportProfileId] already established for VAT-prefix logic.
 *
 * An invoice is eligible when **either** party is in scope (decision 11): the seller has a
 * FR/LU/BE VAT registration (own [SenderProfile.vatId] prefix or an entry in
 * [SenderProfile.additionalVatRegistrations]), or the recipient's billing-address country is
 * FR/LU/BE, or the recipient's [Recipient.vatId] prefix is FR/LU/BE. [sellerInScope] and
 * [recipientInScope] expose the same test per party — the per-party form decides
 * which context fields to show at which party, this object's [isEligible] needs the
 * invoice-level `OR` to decide the engine choice as a whole.
 *
 * **Registrations outside [FACTURX_COUNTRIES] are ignored, not rejected.** A German seller with
 * a DE `vatId` plus LU (and possibly NL, AT, …) entries in `additionalVatRegistrations` is the
 * normal shape — one profile, several registrations — and the LU entry alone already
 * makes the seller in scope; the presence of an NL entry changes nothing about that. The
 * important case this rule must get right is the opposite one: a seller whose *only* additional
 * registration is NL does **not** become Factur-X-eligible through it. The Netherlands is
 * Peppol/NLCIUS territory and belongs to the separate, parked Peppol route (decision 8) — folding
 * an NL registration into this rule would silently offer Factur-X for an invoice that should
 * travel the other route once it exists. Nothing here treats a non-FR/LU/BE country as an error
 * either; it simply carries no weight for this particular predicate.
 */
object FacturXEligibility {

    /** decision 11's country set — the concrete Factur-X scope. Deliberately not "any EU
     *  country with a registration": NL is EU but Peppol territory (decision 8), so it must stay
     *  out of this set even though [Country.SUPPORTED_SELLER_VAT_REGISTRATION_COUNTRIES]
     *  includes it for a different purpose (which registrations a seller may hold at all). */
    val FACTURX_COUNTRIES: Set<Country> = setOf(Country.FR, Country.LU, Country.BE)

    /** Invoice-level form: eligible when at least one party is in scope. Takes the
     *  **already-resolved** parties — resolving which seller/recipient actually applies to an
     *  invoice (e.g. `Invoice.effectiveSender()`'s `selectedSeller ?: senderProfile`) is the
     *  caller's job, not this predicate's; passing a raw `Invoice.senderProfile` here silently
     *  ignores an invoice-level seller override. */
    fun isEligible(seller: SenderProfile?, recipient: Recipient?): Boolean =
        sellerInScope(seller) || recipientInScope(recipient)

    /** Per-party form for the seller — own `vatId` prefix or any FR/LU/BE entry among
     *  [SenderProfile.additionalVatRegistrations]; other-country entries are ignored (see class
     *  KDoc). `null` (no seller yet, e.g. an unfinished draft) is out of scope, never an error. */
    fun sellerInScope(seller: SenderProfile?): Boolean =
        seller?.let { sellerInScope(it.vatId?.value, it.additionalVatRegistrations.map { reg -> reg.country }) } ?: false

    /** The same seller-scope predicate as [sellerInScope], taking the two plain values it
     *  actually needs instead of a loaded [SenderProfile]: read paths that only have a projected
     *  read model (e.g. the UI's `InvoiceDetailDto`/`PartyDto`) can call this directly, without
     *  ever loading (or constructing) a domain [SenderProfile]. */
    fun sellerInScope(vatId: String?, additionalVatRegistrationCountries: Collection<Country>): Boolean =
        vatCountryPrefix(vatId).isFacturXCountry() || additionalVatRegistrationCountries.any { it in FACTURX_COUNTRIES }

    /** Per-party form for the recipient — billing-address country or `vatId` prefix. `null` is
     *  out of scope, never an error. */
    fun recipientInScope(recipient: Recipient?): Boolean =
        recipient?.let { recipientInScope(it.billingAddress?.country, it.vatId?.value) } ?: false

    /** The same recipient-scope predicate as [recipientInScope], taking the plain values
     *  a projected read model already carries (billing-address country, `vatId`) instead of a
     *  loaded [Recipient]. */
    fun recipientInScope(billingCountry: Country?, vatId: String?): Boolean =
        billingCountry.isFacturXCountry() || vatCountryPrefix(vatId).isFacturXCountry()

    /** The invoice-level `isEligible` form for a projected read model: no [SenderProfile]/
     *  [Recipient] loaded or constructed, just the handful of plain values the predicate needs. */
    fun isEligible(
        sellerVatId: String?,
        sellerAdditionalVatRegistrationCountries: Collection<Country>,
        recipientBillingCountry: Country?,
        recipientVatId: String?,
    ): Boolean =
        sellerInScope(sellerVatId, sellerAdditionalVatRegistrationCountries) ||
            recipientInScope(recipientBillingCountry, recipientVatId)

    /** Uppercase-trim, first two characters. A blank, too-short, or otherwise
     *  unparseable VAT id contributes `null` (which [isFacturXCountry] turns into `false`),
     *  never an exception: a half-filled draft must remain evaluable.
     *
     *  The parse itself lives on [Country.fromVatIdPrefix], not here. The per-field country match needs exactly the same
     *  derivation for its per-field country match but asks a *different* question of the result
     *  (membership in a spec's own country set, not in [FACTURX_COUNTRIES]) — so the shared piece
     *  is the derivation, and only the derivation. Two copies of "uppercase, take two, look up"
     *  would drift the moment one of them learns about a VAT id that needs different handling. */
    private fun vatCountryPrefix(vatId: String?): Country? = Country.fromVatIdPrefix(vatId)

    private fun Country?.isFacturXCountry(): Boolean = this != null && this in FACTURX_COUNTRIES
}

/**
 * The repo's own chokepoint principle (`DocumentJobPublisher`'s comment: a UI visibility rule
 * can be circumvented, this checkpoint cannot) — the export panel hiding the engine choice is a
 * UI convenience, not enforcement. A finalized `FACTURX`
 * invoice whose parties are not in Factur-X scope at all must be blocked regardless of how the
 * engine was actually chosen (e.g. an invoice that became ineligible only after master-data was
 * edited post-creation). Same shape as [ArdennesXRules]/[ArdennesXRuleSet].
 */
object FacturXEligibilityRules {

    const val NOT_ELIGIBLE = "FACTURX.NOT_ELIGIBLE"

    fun evaluate(invoice: Invoice): List<ComplianceFinding> {
        if (invoice.documentEngine != DocumentEngine.FACTURX) return emptyList()
        // `effectiveSender()` — the invoice's own selected-seller override (Invoice.selectedSeller)
        // beats `senderProfile`, same resolution CoreCompletenessRules/B2bProfileRuleSet already
        // use. Reading `invoice.senderProfile` directly would ignore that override and could block
        // (or wrongly pass) an invoice based on the wrong seller's VAT registrations.
        if (FacturXEligibility.isEligible(invoice.effectiveSender(), invoice.recipient)) return emptyList()

        return listOf(
            ComplianceFinding(
                ruleId = NOT_ELIGIBLE,
                severity = ComplianceSeverity.ERROR,
                messageKey = "compliance.facturx.notEligible",
            ),
        )
    }
}

/** [FacturXEligibilityRules] as a rule set at the engine, same adapter shape as [ArdennesXRuleSet]. */
object FacturXEligibilityRuleSet : ComplianceRuleSet {
    override val id = "FACTURX_ELIGIBILITY"
    override val levels = setOf(ComplianceLevel.INVOICE)

    override fun evaluate(subject: ComplianceSubject, today: LocalDate): List<ComplianceFinding> {
        val invoice = (subject as? ComplianceSubject.OfInvoice)?.invoice ?: return emptyList()
        return FacturXEligibilityRules.evaluate(invoice)
    }
}
