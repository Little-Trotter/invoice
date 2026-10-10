package de.littletrotter.domain.compliance

import java.time.LocalDate
import de.littletrotter.domain.invoice.DocumentEngine
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.shared.country.Country

/**
 * The SIRET mandatory-field check as a compliance rule, in the same shape as [TaxRules]/
 * [TaxRuleSet]: this rule needs no new [GateBlockReason] value, it goes through the generic
 * [ComplianceEvaluator]/[GateBlockReason.COMPLIANCE_RULE_VIOLATION] path like the others.
 *
 * Blocking rule (Art. 289 CGI): a [DocumentEngine.FACTURX] invoice to a French customer without
 * a SIRET may not finalize. Every other combination is unaffected:
 * - [DocumentEngine.MUSTANG] never triggers this rule, regardless of the customer's country —
 *   Mustang has no engine-side SIRET requirement.
 * - A non-French customer never triggers it either, including Belgium and Luxembourg — SIRET is
 *   a purely French concept, there is no BE/LU equivalent.
 * - A missing NAF code is never blocking — only recommended, per the legal basis.
 *
 * Reads the recipient's *billing* address, mirroring [taxRuleResolutionRequestOrNull]'s existing
 * `buyer.billingAddress?.country` lookup (`TaxCategoryConsistency.kt`) rather than
 * [Invoice.billingAddress] (the frozen invoice-level address) — same source of truth the
 * neighboring tax rule already established for "which country is this buyer in".
 */
object ArdennesXRules {

    const val SIRET_REQUIRED = "ARDENNES_X.SIRET_REQUIRED"

    fun evaluate(invoice: Invoice): List<ComplianceFinding> {
        if (invoice.documentEngine != DocumentEngine.FACTURX) return emptyList()
        val recipient = invoice.recipient ?: return emptyList()
        val country = recipient.billingAddress?.country ?: return emptyList()
        if (country != Country.FR) return emptyList()
        if (!recipient.siret.isNullOrBlank()) return emptyList()

        return listOf(
            ComplianceFinding(
                ruleId = SIRET_REQUIRED,
                severity = ComplianceSeverity.ERROR,
                messageKey = "compliance.ardennesX.siretMissing",
                target = FindingTarget.Buyer,
            ),
        )
    }
}

/** T8 — [ArdennesXRules] as a rule set at the engine, same adapter shape as [TaxRuleSet]. */
object ArdennesXRuleSet : ComplianceRuleSet {
    override val id = "ARDENNES_X"
    override val levels = setOf(ComplianceLevel.INVOICE)

    override fun evaluate(subject: ComplianceSubject, today: LocalDate): List<ComplianceFinding> {
        val invoice = (subject as? ComplianceSubject.OfInvoice)?.invoice ?: return emptyList()
        return ArdennesXRules.evaluate(invoice)
    }
}
