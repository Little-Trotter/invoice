package de.littletrotter.arch

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The aggregate refuses edits of issued documents itself, so application services never ask
 * `isReadOnly()` of the domain before mutating. Checked against production (must pass) and a
 * violating specimen plus a look-alike (must fail / stay silent).
 */
class ReadOnlyGuardArchitectureTest {

    private val productionClasses = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("de.littletrotter")

    private val specimens = ClassFileImporter().importPackages("de.littletrotter.arch.specimen.application.readonly")

    private fun JavaClass.isApplicationClass() =
        packageName.startsWith("de.littletrotter.application") || packageName.contains(".specimen.application.")

    private val domainReadOnlyCall = object : DescribedPredicate<JavaMethodCall>("call a domain isReadOnly()") {
        override fun test(call: JavaMethodCall) =
            call.name == "isReadOnly" && call.targetOwner.packageName.startsWith("de.littletrotter.domain")
    }

    private val noReadOnlyQueryInApplication: ArchRule =
        noClasses().that(DescribedPredicate.describe("application classes") { it.isApplicationClass() })
            .should().callMethodWhere(domainReadOnlyCall)
            .allowEmptyShould(true)

    private fun violationsMention(name: String) =
        noReadOnlyQueryInApplication.evaluate(specimens).failureReport.details.any { it.contains(name) }

    @Test
    fun `application classes do not call isReadOnly`() {
        noReadOnlyQueryInApplication.check(productionClasses)
        assertTrue(violationsMention("SpecimenReadOnlyCaller"))
        assertFalse(violationsMention("SpecimenLookalikeCaller"))
    }
}
