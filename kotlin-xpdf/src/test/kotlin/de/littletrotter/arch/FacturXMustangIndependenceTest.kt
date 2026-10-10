package de.littletrotter.arch

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/**
 * Formerly, kotlin-xpdf's own `ArchitectureRulesTest` proved "FacturX and Mustang
 * never depend on each other" as part of its single-module `layeredArchitecture()` check, because
 * both adapters lived under `de.littletrotter.xpdf..`. Extracting Factur-X into its own Maven
 * module (`kotlin-facturx`, package `de.littletrotter.facturx`) moved it out of that package tree
 * entirely, so that check can no longer see it — `ArchitectureRulesTest` only imports
 * `de.littletrotter.xpdf`. This class is the replacement: it imports both packages at once
 * (something only possible from kotlin-xpdf, since it is the one module that compiles against
 * both — kotlin-facturx never depends on kotlin-xpdf, by construction) and re-proves the exact
 * same guarantee across the new module boundary.
 *
 * Why this still matters after the split: the two are independent, swappable e-invoice-library
 * integrations — the Factur-X validation/embedding stack and the Mustang CII exporter — that both
 * exchange data with `Impl` via `kotlin-shared` DTOs (the "shared-DTO boundary", `Api.mayNotAccessAnyLayer()`
 * — see `ArchitectureRulesTest`'s own class doc). Letting either reach into the other would defeat
 * the point of keeping them independent, whether or not they happen to sit in the same Maven
 * module.
 */
class FacturXMustangIndependenceTest {

    private val classes = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("de.littletrotter.facturx", "de.littletrotter.xpdf.impl.mustang")

    @Test
    fun `facturx must not depend on mustang`() {
        noClasses()
            .that().resideInAPackage("de.littletrotter.facturx..")
            .should().dependOnClassesThat()
            .resideInAPackage("de.littletrotter.xpdf.impl.mustang..")
            .because(
                "FacturX (kotlin-facturx) and Mustang (kotlin-xpdf's impl.mustang) are two " +
                    "independent, swappable e-invoice-library integrations — neither may reach " +
                    "into the other's package, module boundary or not"
            )
            .check(classes)
    }

    @Test
    fun `mustang must not depend on facturx`() {
        noClasses()
            .that().resideInAPackage("de.littletrotter.xpdf.impl.mustang..")
            .should().dependOnClassesThat()
            .resideInAPackage("de.littletrotter.facturx..")
            .because(
                "FacturX (kotlin-facturx) and Mustang (kotlin-xpdf's impl.mustang) are two " +
                    "independent, swappable e-invoice-library integrations — neither may reach " +
                    "into the other's package, module boundary or not"
            )
            .check(classes)
    }
}
