package de.littletrotter.arch

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Screens show and edit data only through the read models and commands of their input ports.
 * A view that holds a persistent domain object could read lazy associations outside a
 * transaction or change state past the aggregate's own methods, so the UI must not depend on
 * any class mapped as a JPA entity or embeddable. Enums and plain value types stay allowed.
 * Checked against production (must pass) and a violating specimen (must fail).
 */
class UiEntityBoundaryArchitectureTest {

    private val productionClasses = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("de.littletrotter")

    private val specimens = ClassFileImporter().importPackages("de.littletrotter.arch.specimen.ui")

    private val uiWithoutEntities = noClasses()
        .that().resideInAnyPackage("de.littletrotter.ui..", "de.littletrotter.arch.specimen.ui..")
        .should().dependOnClassesThat().areAnnotatedWith(Entity::class.java)
        .orShould().dependOnClassesThat().areAnnotatedWith(Embeddable::class.java)
        .because("the UI works on read models and commands, never on persistent domain objects")

    private fun report(classes: JavaClasses) = uiWithoutEntities.evaluate(classes).failureReport.details

    @Test
    fun `production UI depends on no entity or embeddable`() {
        uiWithoutEntities.check(productionClasses)
    }

    @Test
    fun `specimens holding an entity or an embeddable are rejected`() {
        val details = report(specimens)
        assertTrue(details.any { it.contains("ViolatingEntityReader") })
        assertTrue(details.any { it.contains("ViolatingEmbeddableReader") })
    }

    @Test
    fun `a specimen working on a read model stays unreported`() {
        assertFalse(report(specimens).any { it.contains("AcceptedReadModelReader") })
    }
}
