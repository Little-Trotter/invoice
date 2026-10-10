package de.littletrotter.arch

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Invoice and quote share one tax decision core. A second resolver or a second copy of the tax
 * category or rule code in an application module would let the two documents disagree on the same
 * supply, so these types are declared in `de.littletrotter.shared.tax` and nowhere else.
 */
class TaxRuleArchitectureTest {

    private val productionClasses = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages(
            "de.littletrotter.domain",
            "de.littletrotter.application",
            "de.littletrotter.infrastructure",
            "de.littletrotter.ui",
            "de.littletrotter.spi",
        )

    private val rule = classes()
        .that().haveNameMatching(".*\\.(\\w*TaxRule\\w*Resolver\\w*|TaxRuleCode|VatCategory)")
        .should().resideInAPackage("de.littletrotter.shared.tax")
        .because("tax rule resolution and its codes have one source shared by invoice and quote")

    @Test
    fun `no tax rule classes outside the shared tax package`() {
        rule.allowEmptyShould(true).check(productionClasses)
    }

    @Test
    fun `negative specimens declaring tax rule types outside shared are rejected`() {
        val report = rule.evaluate(ClassFileImporter().importPackages("de.littletrotter.arch.specimen")).failureReport.details
        assertTrue(report.any { it.contains("ViolatingTaxRuleResolver") }, "a second resolver must be rejected")
        assertTrue(report.any { it.contains("VatCategory") }, "a second tax category enum must be rejected")
    }
}
