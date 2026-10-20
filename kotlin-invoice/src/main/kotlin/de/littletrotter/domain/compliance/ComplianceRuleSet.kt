package de.littletrotter.domain.compliance

import java.time.LocalDate

/**
 * A named set of related rules, side-effect free — it reads, it doesn't write or log.
 *
 * Declares its own [levels] rather than deciding internally whether to apply: otherwise "which
 * rules run here" would only be answerable by reading every implementation.
 */
interface ComplianceRuleSet {
    /** Stable id, e.g. `"CORE"`, `"PERIOD"`, `"UNIT"`, `"B2B"`. */
    val id: String

    val levels: Set<ComplianceLevel>

    /** Called only with a [subject] whose `level` is in [levels]. [today] is the real calendar
     *  date (a domain object may not read the clock itself, TimeSourceArchitectureTest) — only
     *  [de.littletrotter.domain.compliance.TaxRuleSet] currently uses it (the R1 EU-B2B-target
     *  allowlist, see [TaxRuleResolver]'s own KDoc for why that needs "today", not the invoice's
     *  own date); every other rule set ignores the parameter. */
    fun evaluate(subject: ComplianceSubject, today: LocalDate): List<ComplianceFinding>
}

/**
 * The single place where "all findings for this subject" are produced. Pure, no side effects.
 * Finding order follows rule-set order and is stable, so a test can pin it down.
 */
object ComplianceEvaluator {

    fun evaluate(
        subject: ComplianceSubject,
        today: LocalDate,
        ruleSets: List<ComplianceRuleSet> = ComplianceRuleSets.DEFAULT,
    ): List<ComplianceFinding> =
        ruleSets.filter { subject.level in it.levels }.flatMap { it.evaluate(subject, today) }

    fun evaluate(
        invoice: de.littletrotter.domain.invoice.Invoice,
        today: LocalDate,
        ruleSets: List<ComplianceRuleSet> = ComplianceRuleSets.DEFAULT,
    ): List<ComplianceFinding> = evaluate(ComplianceSubject.OfInvoice(invoice), today, ruleSets)
}

/**
 * The built-in rule sets. A plain list, not a CDI collection: every entry here reads only the
 * invoice aggregate (or, for SELLER/BUYER, stored master data) — no repository dependency.
 * `B2bProfileRuleSet` is deliberately **not** included: it needs the B2B field catalog and the
 * `b2b_field_value` table, which the aggregate can't reach via JPA, so a caller with repository
 * access ([de.littletrotter.application.invoice.InvoiceLifecycleService]) adds it explicitly via
 * [de.littletrotter.domain.compliance.evaluateCompliance]'s `ruleSets` parameter.
 */
object ComplianceRuleSets {
    val DEFAULT: List<ComplianceRuleSet> = listOf(
        SellerDataRules,
        BuyerDataRules,
        CoreCompletenessRules,
        PeriodConsistencyRuleSet,
        DueDateRules,
        UnitConsistencyRuleSet,
        TaxRuleSet,
        SchemeIdComplianceRules,
    )
}
