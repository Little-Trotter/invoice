package de.littletrotter.ui.components.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * kotlin-ui-components is a plain Vaadin component jar every host application depends on — it
 * must never grow a dependency back onto one host's domain, application layer or CDI/JPA runtime,
 * or it stops being reusable by a second host. The first test below guards the guard itself: an
 * empty import would make every rule after it pass for the wrong reason (nothing to violate), the
 * same failure mode a broken package filter would produce silently.
 */
class UiComponentsBoundaryTest {
    private val classes = ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("de.littletrotter.ui.components")

    @Test
    fun `the module actually contains classes to check — an empty import would make every rule below vacuous`() {
        assertTrue(classes.toList().isNotEmpty())
    }

    @Test
    fun `no class depends on the mail SCS, the email contract, or a host application's own layers`() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage(
            "de.littletrotter.email..",
            "de.littletrotter.shared.mail..",
            "de.littletrotter.application..",
            "de.littletrotter.domain..",
            "de.littletrotter.infrastructure..",
        ).check(classes)
    }

    @Test
    fun `no class depends on CDI or JPA — a host's runtime, never this component's own`() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage(
            "jakarta.enterprise..",
            "jakarta.persistence..",
        ).check(classes)
    }
}
