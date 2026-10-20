package de.littletrotter.domain.compliance

import java.time.LocalDate
import de.littletrotter.domain.invoice.effectiveSender
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.shared.b2b.codes.B2bCodeList
import de.littletrotter.shared.b2b.codes.FacturXProfile

/**
 * Scheme-ID membership (ISO 6523 ICD for [de.littletrotter.domain.masterdata
 * .PartyIdentifier], EAS for [de.littletrotter.domain.masterdata.ElectronicAddress]) as a
 * **blocking** compliance finding.
 *
 * `PartyIdentifier.of`/`ElectronicAddress.of` stay lenient (non-blank only): master data is
 * edited iteratively, and a hard constructor throw would block saving unrelated changes on a
 * record. This rule set — not the constructor — is the actual enforcement point: a code-list
 * violation must never reach the generation path, so it participates in
 * [ComplianceRuleSets.DEFAULT] both directly at [ComplianceLevel.SELLER]/[ComplianceLevel.BUYER]
 * (the compliance panel surfaces the finding right after saving master data) and at
 * [ComplianceLevel.INVOICE] via the invoice's *effective* seller/buyer (so
 * [de.littletrotter.application.invoice.FinalizationPolicy.enforceComplianceRules] re-checks it —
 * and therefore the F4 finalization gate — at finalization time, catching a value that was
 * persisted before a ZUGFeRD code-list update).
 *
 * Not built on [ComplianceCheck]/[ComplianceCheck.findingOrNull] like the other rule sets in
 * this package: a scheme-ID finding must name the offending value in its message (`{0}`), and
 * [ComplianceCheck] carries no `args` field — [ComplianceFinding] is therefore constructed
 * directly here, the same pattern [B2bProfileRules] and `PeriodConsistencyRules` already use for
 * the same reason. Consequently there is no `checks(...)`/all-points variant here (unlike
 * [SellerDataRules]/[BuyerDataRules]) — nothing today shows an *satisfied* scheme-ID check as a
 * checklist line item; add one if that UI need arises.
 *
 * A standalone file rather than an addition to [SellerDataRules]/[BuyerDataRules]/
 * [CoreCompletenessRules]: this rule set derives the invoice's effective parties itself
 * ([effectiveSender], `invoice.recipient`) instead of reusing their `checks(...)` helpers, so it
 * needs no touch to either file — the only shared-file touch is [ComplianceRuleSets.DEFAULT]
 * itself.
 */
object SchemeIdComplianceRules : ComplianceRuleSet {

    const val SELLER_IDENTIFIER_SCHEME = "CORE.SELLER_IDENTIFIER_SCHEME"
    const val SELLER_ELECTRONIC_ADDRESS_SCHEME = "CORE.SELLER_ELECTRONIC_ADDRESS_SCHEME"
    const val BUYER_IDENTIFIER_SCHEME = "CORE.BUYER_IDENTIFIER_SCHEME"
    const val BUYER_ELECTRONIC_ADDRESS_SCHEME = "CORE.BUYER_ELECTRONIC_ADDRESS_SCHEME"

    override val id = "SCHEME_ID"
    override val levels = setOf(ComplianceLevel.SELLER, ComplianceLevel.BUYER, ComplianceLevel.INVOICE)

    override fun evaluate(subject: ComplianceSubject, today: LocalDate): List<ComplianceFinding> = when (subject) {
        is ComplianceSubject.OfSeller -> sellerFindings(subject.profile)
        is ComplianceSubject.OfBuyer -> buyerFindings(subject.recipient)
        is ComplianceSubject.OfInvoice ->
            sellerFindings(subject.invoice.effectiveSender()) + buyerFindings(subject.invoice.recipient)
        else -> emptyList()
    }

    fun sellerFindings(profile: SenderProfile?): List<ComplianceFinding> = listOfNotNull(
        schemeFinding(
            CheckIdentity(SELLER_IDENTIFIER_SCHEME, "compliance.core.seller.identifierScheme"), listOf(B2bTerm.BT_29),
            FindingTarget.Seller, SchemeValueCheck(profile?.sellerIdentifier?.schemeId, B2bCodeList.PARTY_ID_SCHEME_ICD),
        ),
        schemeFinding(
            CheckIdentity(SELLER_ELECTRONIC_ADDRESS_SCHEME, "compliance.core.seller.electronicAddressScheme"), listOf(B2bTerm.BT_34),
            FindingTarget.Seller, SchemeValueCheck(profile?.electronicAddress?.schemeId, B2bCodeList.ELECTRONIC_ADDRESS_SCHEME_EAS),
        ),
    )

    fun buyerFindings(recipient: Recipient?): List<ComplianceFinding> = listOfNotNull(
        schemeFinding(
            CheckIdentity(BUYER_IDENTIFIER_SCHEME, "compliance.core.buyer.identifierScheme"), listOf(B2bTerm.BT_46),
            FindingTarget.Buyer, SchemeValueCheck(recipient?.buyerIdentifier?.schemeId, B2bCodeList.PARTY_ID_SCHEME_ICD),
        ),
        schemeFinding(
            CheckIdentity(BUYER_ELECTRONIC_ADDRESS_SCHEME, "compliance.core.buyer.electronicAddressScheme"), listOf(B2bTerm.BT_49),
            FindingTarget.Buyer, SchemeValueCheck(recipient?.electronicAddress?.schemeId, B2bCodeList.ELECTRONIC_ADDRESS_SCHEME_EAS),
        ),
    )

    /** [ruleId]/[messageKey] bundled so [schemeFinding] takes one parameter for them instead of
     *  two (LongParameterList) — every call site above only ever varies them together. */
    private data class CheckIdentity(val ruleId: String, val messageKey: String)

    /** The scheme value under test and the code list it must belong to — bundled for the same
     *  reason as [CheckIdentity]. */
    private data class SchemeValueCheck(val schemeId: String?, val list: B2bCodeList)

    /**
     * `null` (no finding) when there is no scheme at all yet — an absent value+scheme pair is a
     * completeness gap ([SellerDataRules]/[BuyerDataRules]'s job), not a code-list membership
     * defect. Fires only once a scheme value actually exists and it fails EN16931 membership.
     */
    private fun schemeFinding(
        id: CheckIdentity,
        terms: List<B2bTerm>,
        target: FindingTarget,
        scheme: SchemeValueCheck,
    ): ComplianceFinding? {
        if (scheme.schemeId.isNullOrBlank() || scheme.list.contains(scheme.schemeId, FacturXProfile.EN16931)) return null
        return ComplianceFinding(
            ruleId = id.ruleId,
            severity = ComplianceSeverity.ERROR,
            messageKey = id.messageKey,
            businessTerms = terms,
            target = target,
            args = listOf(scheme.schemeId),
        )
    }
}
