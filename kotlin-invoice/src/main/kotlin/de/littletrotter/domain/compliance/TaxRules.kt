package de.littletrotter.domain.compliance

import de.littletrotter.shared.tax.TaxRuleResolution
import de.littletrotter.shared.tax.TaxRuleResolver
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.shared.tax.VatCategory
import de.littletrotter.shared.b2b.B2bTerm
import java.time.LocalDate

/**
 * [de.littletrotter.shared.tax.TaxRuleResolver]'s outcomes as [ComplianceFinding]s, next
 * to the structural throws ([MixedTaxCategoryException], and
 * [de.littletrotter.domain.invoice.validateReverseChargeItemConsistency]/
 * [assertReverseChargeRecipientEligibility]) that already gate `→ ISSUED` in
 * [de.littletrotter.application.invoice.InvoiceLifecycleService.transition] and stay exactly as
 * strict as before — this rule set adds *visibility* (at save time, in the checklist, in the
 * OBSERVE-stage log), it does not replace or loosen either throw.
 *
 * `CATEGORY_MISMATCH` below still reasons from the *recipient context* (via [TaxRuleResolver]),
 * not from [de.littletrotter.domain.invoice.Invoice.reverseCharge] — it predates that change and
 * remains a softer, informational finding (`OBSERVE`-staged by default), not itself a
 * `→ ISSUED` gate; the hard gate is the header-decision-vs-items check named above.
 *
 * | Rule | Source | Severity | Statement |
 * |---|---|---|---|
 * | `TAX.MIXED_CATEGORY` | house rule | ERROR | reverse-charge and standard-rate lines mixed on one invoice |
 * | `TAX.CATEGORY_MISMATCH` | house rule | ERROR | at least one line contradicts the category expected from recipient context |
 * | `TAX.<TaxRuleResolutionError>` | R5 | WARNING | the resolver couldn't propose a rule automatically |
 *
 * The `Blocked`-resolution findings stay `WARNING`: nothing else in this codebase blocks on them
 * today, and turning them into a blocking `ERROR` here would be a behavior change this rule set
 * was not asked to make — they surface the resolver's reasoning, they don't newly gate anything.
 */
object TaxRules {

    const val MIXED_CATEGORY = "TAX.MIXED_CATEGORY"
    const val CATEGORY_MISMATCH = "TAX.CATEGORY_MISMATCH"

    fun evaluate(invoice: Invoice, today: LocalDate): List<ComplianceFinding> = buildList {
        mixedCategoryFinding(invoice)?.let { add(it) }

        val request = invoice.taxRuleResolutionRequestOrNull() ?: return@buildList
        resolutionFinding(invoice, TaxRuleResolver.resolve(request, today))?.let { add(it) }
    }

    private fun mixedCategoryFinding(invoice: Invoice): ComplianceFinding? {
        if (!invoice.itemList.hasMixedReverseChargeAndStandard()) return null
        return ComplianceFinding(
            ruleId = MIXED_CATEGORY,
            severity = ComplianceSeverity.ERROR,
            messageKey = "compliance.tax.mixedCategory",
            businessTerms = listOf(B2bTerm.BT_151),
        )
    }

    private fun resolutionFinding(invoice: Invoice, resolution: TaxRuleResolution): ComplianceFinding? = when (resolution) {
        is TaxRuleResolution.Blocked -> ComplianceFinding(
            ruleId = "TAX.${resolution.error.name}",
            severity = ComplianceSeverity.WARNING,
            messageKey = "compliance.tax.resolutionBlocked",
            args = listOf(resolution.detail),
        )
        is TaxRuleResolution.Proposed -> categoryMismatchFinding(invoice, resolution)
    }

    private fun categoryMismatchFinding(invoice: Invoice, resolution: TaxRuleResolution.Proposed): ComplianceFinding? {
        if (resolution.category != VatCategory.REVERSE_CHARGE && resolution.category != VatCategory.STANDARD) return null
        val conflicting = invoice.itemList.mapNotNull { it.taxCategory }.toSet().filter { actual ->
            (resolution.category == VatCategory.REVERSE_CHARGE && actual == VatCategory.STANDARD) ||
                (resolution.category == VatCategory.STANDARD && actual == VatCategory.REVERSE_CHARGE)
        }
        if (conflicting.isEmpty()) return null
        return ComplianceFinding(
            ruleId = CATEGORY_MISMATCH,
            severity = ComplianceSeverity.ERROR,
            messageKey = "compliance.tax.categoryMismatch",
            businessTerms = listOf(B2bTerm.BT_151),
            args = listOf(resolution.category.code, conflicting.first().code),
        )
    }
}

/** [TaxRules] as a rule set at the engine (same adapter shape as
 *  [PeriodConsistencyRuleSet]/[UnitConsistencyRuleSet]: the evaluation itself is a pure function
 *  on [Invoice], this only adds level and id). */
object TaxRuleSet : ComplianceRuleSet {
    override val id = "TAX"
    override val levels = setOf(ComplianceLevel.INVOICE)

    override fun evaluate(subject: ComplianceSubject, today: LocalDate): List<ComplianceFinding> {
        val invoice = (subject as? ComplianceSubject.OfInvoice)?.invoice ?: return emptyList()
        return TaxRules.evaluate(invoice, today)
    }
}
