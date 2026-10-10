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
 * property (e.g. a private helper's local parameter). None exist in kotlin-shared at the time
 * this test was added; see kotlin-invoice's `MoneyArchitectureTest` for the one documented
 * precedent of that shape ([de.littletrotter.domain.invoice.InvoiceItem]-adjacent UI helpers).
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
    private val fieldAllowlist: Map<String, String> = mapOf(
        "de.littletrotter.shared.Money.amount" to
            "Money's own backing value — the one BigDecimal every other money-named field in the " +
            "project is required to wrap instead of holding directly. Structurally exempt: this " +
            "field IS the enforcement point, not a violation of it.",
    )

    /** Allowlist of methods whose *name* is money-shaped but whose return type is deliberately a
     *  raw BigDecimal, each with why. Format: "FullyQualifiedClassName#methodName". */
    private val methodReturnAllowlist: Map<String, String> = emptyMap()

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
