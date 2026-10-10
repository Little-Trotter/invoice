package de.littletrotter.arch

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaField
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Enforces the rule stated in [de.littletrotter.shared.Money]'s own KDoc: a monetary amount is
 * represented by exactly one class, [de.littletrotter.shared.Money], everywhere in the project —
 * `BigDecimal` stays reserved for quantities, percentages and weighting factors. Without this
 * test the rule is only a comment someone can silently violate the next time a field is added;
 * this turns it into a build failure instead.
 *
 * Scope, and why: ArchUnit 1.5.0's domain model does not track constructor/method *parameter
 * names* ([com.tngtech.archunit.core.domain.JavaParameter] exposes only index and type, never a
 * name — confirmed against the pinned version, not assumed), so "a money-named constructor
 * parameter" cannot be matched by name directly. In Kotlin this gap is closed in practice: every
 * primary-constructor `val`/`var` parameter of a `data class` (the DTOs and value carriers this
 * rule cares about) compiles to a backing *field* with the same name, which this rule does check.
 * What is *not* caught: a money-named parameter of an ordinary function that is not also a class
 * property — this module's item-dialog UI helpers (`InvoiceDetailView.calculateAmounts` and
 * friends) are exactly that shape, and are called out in the allowlist below anyway (with their
 * own justification) even though this rule cannot mechanically enforce them, so the reasoning is
 * on record next to the rule it is an exception to.
 */
class MoneyArchitectureTest {

    private val classes = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("de.littletrotter")

    /** Case-insensitive: matches `netAmount`, `unitPriceNet`, `grandTotal`, `avgPrice`, a
     *  `sum(...)` accumulator, etc. Deliberately does *not* match `quantity`, `taxRate`,
     *  `discountPercent`, `defaultQuantity` — none of those contain amount/price/total/sum. */
    private val moneyNamePattern = Regex("(?i).*(amount|price|total|sum).*")

    /**
     * Allowlist of fields that are money-shaped by name but *not* [de.littletrotter.shared.Money]
     * on purpose, each with why. Format: "FullyQualifiedClassName.fieldName".
     */
    private val fieldAllowlist: Map<String, String> = buildMap {
        put(
            "de.littletrotter.shared.Money.amount",
            "Money's own backing value — the one BigDecimal every other money-named field in the " +
                "project is required to wrap instead of holding directly. Structurally exempt: this " +
                "field IS the enforcement point, not a violation of it. Visible here (not just in " +
                "kotlin-shared's own copy of this test) because ArchUnit scans the whole classpath, " +
                "including the kotlin-shared dependency this module compiles against.",
        )
        val statsMoneyFieldJustification =
            "H27-59-Folge (PO-Entscheid 29.08.2026) — read-only JPA mapping of a `v_stat_*` view " +
                "(V1__baseline_schema.sql). The migration's own comment on that view hierarchy is the " +
                "reason: \"Amounts stay NUMERIC so the Kotlin layer reads BigDecimal directly and " +
                "bypasses the Hibernate MoneyConverter path, which is ignored for scalar SUM/GROUP BY " +
                "projections.\" InvoiceStatisticsQueryServiceImpl wraps every value read off these " +
                "fields in Money.of(...) before it reaches a DTO — see each de.littletrotter" +
                ".infrastructure.persistence.stats entity's own KDoc."
        for (entity in listOf("StatInvoiceBase", "StatYearly", "StatQuarterly", "StatMonthly", "StatDaily")) {
            for (field in listOf("netAmount", "vatAmount", "grossAmount")) {
                put("de.littletrotter.infrastructure.persistence.stats.$entity.$field", statsMoneyFieldJustification)
            }
        }
        for (entity in listOf("StatItemBase", "StatItemYearly", "StatItemQuarterly", "StatItemMonthly")) {
            put("de.littletrotter.infrastructure.persistence.stats.$entity.netAmount", statsMoneyFieldJustification)
        }
    }

    /** Allowlist of methods whose *name* is money-shaped but whose return type is deliberately a
     *  raw BigDecimal, each with why. Format: "FullyQualifiedClassName#methodName". */
    private val methodReturnAllowlist: Map<String, String> = mapOf(
        "de.littletrotter.domain.reference.PerDiemRate#calculateClaimableAmount" to
            "Task Q3 (see the method's own KDoc): signature stays BigDecimal in/out on purpose " +
            "— called from PerDiemService and directly from a UI dialog, neither of which that " +
            "task's claimed files included. Internally routed through Money regardless " +
            "(calculateDeductionWithContribution/calculateMealDeduction do the actual math).",
        "de.littletrotter.domain.reference.PerDiemRate#calculateClaimableAmount\$default" to
            "Kotlin-compiler-generated overload for calculateClaimableAmount's defaulted " +
            "employerContribution parameter — same justification as the method above.",
    )

    @Test
    fun `no money-named field is a raw BigDecimal`() {
        val violations = mutableListOf<String>()
        classes.forEach { clazz -> checkFields(clazz, violations) }
        if (violations.isNotEmpty()) {
            throw AssertionError(
                "Money-named field(s) typed raw BigDecimal instead of Money (see Money's KDoc " +
                    "and this test's KDoc for the rule and its allowlist):\n" +
                    violations.joinToString("\n") { "  - $it" }
            )
        }
    }

    @Test
    fun `no money-named method returns a raw BigDecimal`() {
        val violations = mutableListOf<String>()
        classes.forEach { clazz -> checkMethodReturnTypes(clazz, violations) }
        if (violations.isNotEmpty()) {
            throw AssertionError(
                "Money-named method(s) returning raw BigDecimal instead of Money:\n" +
                    violations.joinToString("\n") { "  - $it" }
            )
        }
    }

    private fun checkFields(clazz: JavaClass, violations: MutableList<String>) {
        for (field: JavaField in clazz.fields) {
            if (!moneyNamePattern.matches(field.name)) continue
            if (field.rawType.fullName != BigDecimal::class.java.name) continue
            val key = "${clazz.fullName}.${field.name}"
            if (fieldAllowlist.containsKey(key)) continue
            violations += key
        }
    }

    /** Kotlin `val`/`var` property accessors (`getAmount`/`setAmount`) mirror a field this rule
     *  already checks via [checkFields] — skip them here so one violation isn't reported twice
     *  under two different names, and so a field's allowlist entry doesn't need a second, method-
     *  keyed twin. */
    private val syntheticAccessorPattern = Regex("^(get|set)[A-Z].*")

    private fun checkMethodReturnTypes(clazz: JavaClass, violations: MutableList<String>) {
        for (method: JavaMethod in clazz.methods) {
            if (syntheticAccessorPattern.matches(method.name)) continue
            if (!moneyNamePattern.matches(method.name)) continue
            if (method.rawReturnType.fullName != BigDecimal::class.java.name) continue
            val key = "${clazz.fullName}#${method.name}"
            if (methodReturnAllowlist.containsKey(key)) continue
            violations += key
        }
    }
}
