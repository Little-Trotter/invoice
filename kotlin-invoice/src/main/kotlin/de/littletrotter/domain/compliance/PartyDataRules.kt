package de.littletrotter.domain.compliance

import java.time.LocalDate
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.shared.b2b.B2bTerm

/**
 * The master-data level of rule evaluation. Lives **once** here and is used by both levels:
 * directly at the master record (`ComplianceSubject.OfSeller`/`OfBuyer`) and in invoice context
 * via [CoreCompletenessRules], which calls the same `checks(...)` with the invoice's *effective*
 * parties. Same `ruleId`, same `messageKey`, different effect — the asymmetry (the seller form
 * blocks on save, the invoice doesn't) stays with the caller, where it belongs.
 *
 * The entities' `validate()` throws are left untouched: they guard an entity invariant on
 * persistence, not the business evaluation. Switching them to these rules is a separate change
 * with its own risk.
 */
object SellerDataRules : ComplianceRuleSet {

    const val IDENTITY = "CORE.SELLER_IDENTITY"
    const val EMAIL = "CORE.SELLER_EMAIL"
    const val ADDRESS = "CORE.SELLER_ADDRESS"
    const val IBAN = "CORE.SELLER_IBAN"
    const val TAX_INFO = "CORE.SELLER_TAX_INFO"

    override val id = "SELLER"
    override val levels = setOf(ComplianceLevel.SELLER)

    override fun evaluate(subject: ComplianceSubject, today: LocalDate): List<ComplianceFinding> {
        val profile = (subject as? ComplianceSubject.OfSeller)?.profile ?: return emptyList()
        return checks(profile, profile.billingAddress).findings()
    }

    /**
     * @param billingAddress passed separately because the invoice may carry a **deviating**
     *   billing address; at the master record it's simply its own.
     */
    fun checks(profile: SenderProfile?, billingAddress: Address?): List<ComplianceCheck> = listOf(
        check(CheckIdentity(IDENTITY, "compliance.core.seller.identity"), ComplianceSeverity.ERROR, listOf(B2bTerm.BT_27),
            profile != null && profile.name.isNotBlank() && profile.invoicePrefix.isNotBlank()),
        check(CheckIdentity(EMAIL, "compliance.core.seller.email"), ComplianceSeverity.ERROR, listOf(B2bTerm.BT_43),
            !profile?.email?.value.isNullOrBlank()),
        // BG-5 (Business Group "Seller postal address") — no B2bTerm equivalent, dropped; see
        // CoreCompletenessRules for the same BG-vs-BT reasoning.
        check(CheckIdentity(ADDRESS, "compliance.core.seller.address"), ComplianceSeverity.ERROR, emptyList(),
            billingAddress.isPostalAddressComplete()),
        check(CheckIdentity(IBAN, "compliance.core.seller.iban"), ComplianceSeverity.ERROR, listOf(B2bTerm.BT_84),
            !profile?.iban?.value.isNullOrBlank()),
        check(CheckIdentity(TAX_INFO, "compliance.core.seller.taxInfo"), ComplianceSeverity.ERROR, listOf(B2bTerm.BT_31, B2bTerm.BT_32),
            profile != null && profile.taxNumber != null && profile.vatId != null),
    )

    /** [ruleId]/[messageKey] bundled so [check] takes one parameter for them instead of two
     *  (LongParameterList) — every call above only ever varies them together. */
    private data class CheckIdentity(val ruleId: String, val messageKey: String)

    private fun check(
        id: CheckIdentity,
        severity: ComplianceSeverity,
        terms: List<B2bTerm>,
        satisfied: Boolean,
    ) = ComplianceCheck(id.ruleId, severity, id.messageKey, FindingTarget.Seller, terms, satisfied)
}

/** The buyer-side counterpart — see [SellerDataRules] for rationale. */
object BuyerDataRules : ComplianceRuleSet {

    const val IDENTITY = "CORE.BUYER_IDENTITY"
    const val EMAIL = "CORE.BUYER_EMAIL"
    const val VAT_ID = "CORE.BUYER_VAT_ID"
    const val ADDRESS = "CORE.BUYER_ADDRESS"

    override val id = "BUYER"
    override val levels = setOf(ComplianceLevel.BUYER)

    override fun evaluate(subject: ComplianceSubject, today: LocalDate): List<ComplianceFinding> {
        val recipient = (subject as? ComplianceSubject.OfBuyer)?.recipient ?: return emptyList()
        return checks(recipient, recipient.billingAddress, recipient.deliveryAddress).findings()
    }

    /**
     * @param deliveryAddress counts equally to the billing address for [ADDRESS]: an invoice is
     *   addressable as soon as **one** complete address exists. The finer distinction of which
     *   one is missing is carried by the `INFO` checks in [CoreCompletenessRules].
     */
    fun checks(
        recipient: Recipient?,
        billingAddress: Address?,
        deliveryAddress: Address?,
    ): List<ComplianceCheck> = listOf(
        check(IDENTITY, "compliance.core.buyer.identity", listOf(B2bTerm.BT_44),
            recipient != null && recipient.name.isNotBlank() && recipient.customerNumber != null),
        check(EMAIL, "compliance.core.buyer.email", listOf(B2bTerm.BT_58),
            !recipient?.email?.value.isNullOrBlank()),
        check(VAT_ID, "compliance.core.buyer.vatId", listOf(B2bTerm.BT_48),
            recipient?.vatId != null),
        // BG-8 (Business Group "Buyer postal address") — dropped, see [SellerDataRules.checks].
        check(ADDRESS, "compliance.core.buyer.address", emptyList(),
            billingAddress.isPostalAddressComplete() || deliveryAddress.isPostalAddressComplete()),
    )

    private fun check(ruleId: String, messageKey: String, terms: List<B2bTerm>, satisfied: Boolean) =
        ComplianceCheck(ruleId, ComplianceSeverity.ERROR, messageKey, FindingTarget.Buyer, terms, satisfied)
}

/** Complete enough to deliver a document. Deliberately no country check: the export derives
 *  that from the master record and falls back to `"DE"` (`PartyDto.country`). */
internal fun Address?.isPostalAddressComplete(): Boolean =
    this != null && street.isNotBlank() && postalCode.value.isNotBlank() && city.isNotBlank()

/**
 * The seller's telephone number (BT-42; BR-DE-6/BR-DE-27, German KoSIT CIUS). Deliberately
 * **not** folded into [SellerDataRules]: that rule set feeds [CoreCompletenessRules] and therefore
 * the draft-time completeness checklist and [de.littletrotter.domain.compliance.isTaxRelevantComplete],
 * where an incomplete seller profile has to stay a valid intermediate state (see [SellerDataRules]'s
 * own KDoc for the identical EMAIL/ADDRESS/IBAN tension). BT-42 only became mandatory once every
 * invoice this application had ever produced already lacked it — there is no installed base of
 * already-`SENT` invoices a staged rollout would need to protect, so it is evaluated unconditionally
 * and only at the point that actually matters: [de.littletrotter.application.invoice
 * .FinalizationPolicy.enforceSellerTelephoneNumber], the `→ SENT` transition, alongside that
 * policy's other unconditional structural checks (e.g. `enforceMandatorySellerFields`).
 */
object SellerPhoneRule {

    /** BT-42 missing entirely. */
    const val MISSING = "CORE.SELLER_PHONE"

    /** BT-42 present but with fewer than [MIN_DIGITS] digits — BR-DE-27's own threshold. */
    const val INSUFFICIENT_DIGITS = "CORE.SELLER_PHONE_DIGITS"

    private const val MIN_DIGITS = 3

    /**
     * `null` once [phone] is usable for BT-42; otherwise the one finding that names why not —
     * never both at once, so a caller that blocks on this carries exactly one, unambiguous
     * message rather than two overlapping complaints about the same field.
     */
    fun evaluate(phone: String?): ComplianceFinding? = when {
        phone.isNullOrBlank() -> finding(MISSING, "compliance.core.seller.phone")
        phone.count(Char::isDigit) < MIN_DIGITS -> finding(INSUFFICIENT_DIGITS, "compliance.core.seller.phoneDigits")
        else -> null
    }

    private fun finding(ruleId: String, messageKey: String) =
        ComplianceFinding(ruleId, ComplianceSeverity.ERROR, messageKey, listOf(B2bTerm.BT_42), FindingTarget.Seller)
}
