package de.littletrotter.arch

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods
import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import jakarta.persistence.MappedSuperclass
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Persistent domain objects change state only through their intent methods. A public setter would
 * let any caller put an aggregate into a state its invariants never allowed, so mapped domain
 * classes keep their setters closed to JPA, and nothing outside the domain calls a setter of such a
 * class. Checked against production (must pass) and violating specimens (must fail).
 */
class EntitySetterArchitectureTest {

    private val productionClasses = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("de.littletrotter")

    private val specimens = ClassFileImporter().importPackages(SPECIMEN_PACKAGE)

    private val noPublicSetters: ArchRule = noMethods()
        .that().areDeclaredInClassesThat(persistentDomainType)
        .and().haveNameMatching(SETTER_NAME)
        .should().haveModifier(JavaModifier.PUBLIC)
        .because("mapped domain classes expose intent methods, never setters")

    private val noSetterCallsOutsideDomain: ArchRule = noClasses()
        .that().resideOutsideOfPackage("de.littletrotter.domain..")
        .should().callMethodWhere(setterOfPersistentDomainType)
        .because("state changes go through the aggregate's own methods")

    private fun report(rule: ArchRule, classes: JavaClasses) = rule.evaluate(classes).failureReport.details

    @Test
    fun `production mapped domain classes expose no public setter`() {
        noPublicSetters.check(productionClasses)
    }

    @Test
    fun `production code outside the domain calls no setter of a mapped domain class`() {
        noSetterCallsOutsideDomain.check(productionClasses)
    }

    @Test
    fun `specimen with a public setter is rejected and its closed sibling stays unreported`() {
        val details = report(noPublicSetters, specimens)
        assertTrue(details.any { it.contains("ViolatingOpenSetterValue") })
        assertFalse(details.any { it.contains("AcceptedClosedSetterValue") })
    }

    @Test
    fun `specimen calling a setter from outside the domain is rejected`() {
        val details = report(noSetterCallsOutsideDomain, specimens)
        assertTrue(details.any { it.contains("ViolatingSetterCaller") })
        assertFalse(details.any { it.contains("AcceptedIntentCaller") })
    }

    private companion object {
        const val SPECIMEN_PACKAGE = "de.littletrotter.arch.specimen.entity"
        const val SETTER_NAME = "set[A-Z].*"
        private val PERSISTENT_ANNOTATIONS = listOf(Entity::class.java, Embeddable::class.java, MappedSuperclass::class.java)

        val persistentDomainType = object : DescribedPredicate<JavaClass>("mapped domain classes") {
            override fun test(type: JavaClass): Boolean =
                (type.packageName.startsWith("de.littletrotter.domain") || type.packageName.startsWith(SPECIMEN_PACKAGE)) &&
                    PERSISTENT_ANNOTATIONS.any { type.isAnnotatedWith(it) }
        }

        val setterOfPersistentDomainType = object : DescribedPredicate<JavaMethodCall>("a setter of a mapped domain class") {
            override fun test(call: JavaMethodCall): Boolean =
                call.target.name.matches(Regex(SETTER_NAME)) && persistentDomainType.test(call.targetOwner)
        }
    }
}
