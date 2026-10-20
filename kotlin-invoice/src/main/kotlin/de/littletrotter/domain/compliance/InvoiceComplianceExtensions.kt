package de.littletrotter.domain.compliance

import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.invoice.Invoice
import java.time.LocalDate

/**
 * These live here, not in `domain/invoice/`, so the dependency direction stays clear:
 * `domain.compliance` reads `domain.invoice`, never the other way around.
 */

/** All findings for this invoice, from all built-in rule sets — the single place for it. */
fun Invoice.evaluateCompliance(
    today: LocalDate,
    ruleSets: List<ComplianceRuleSet> = ComplianceRuleSets.DEFAULT,
): List<ComplianceFinding> = ComplianceEvaluator.evaluate(ComplianceSubject.OfInvoice(this), today, ruleSets)

/** Completeness only — none of the mandatory fields are missing. */
fun Invoice.isTaxRelevantComplete(): Boolean = !CoreCompletenessRules.checks(this).findings().hasBlocking()

/**
 * Throws as soon as a mandatory field is missing.
 *
 * The thrown [InvoiceValidationError] is always `INVOICE_NUMBER_MISSING`, **regardless of which
 * field is actually missing** — pre-existing behavior, kept unchanged here. It's still wrong:
 * the caller gets a misleading error code. Fixing it means using [evaluateCompliance] and the
 * first blocking finding's `ruleId` instead.
 */
fun Invoice.validateTaxRelevantCompleteness() {
    CoreCompletenessRules.checks(this).findings().firstOrNull { it.isBlocking } ?: return
    throw InvoiceValidationException(InvoiceValidationError.INVOICE_NUMBER_MISSING)
}
