package de.littletrotter.arch

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/**
 * kotlin-shared is a Published Language (message contract) between kotlin-invoice and
 * kotlin-xpdf, deliberately kept free of kotlin-invoice's domain model so both modules stay
 * independently deployable ("Erst Reader, dann Producer"). SchemedIdentifierDto already
 * duplicates kotlin-invoice's value objects by hand for this reason; this test turns that
 * discipline into a gate instead of a convention that only holds as long as nobody imports the
 * shortcut.
 */
class ArchitectureRulesTest {

    private val classes = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("de.littletrotter")

    @Test
    fun `kotlin-shared must not depend on kotlin-invoice's domain package`() {
        noClasses()
            .that().resideInAPackage("de.littletrotter.shared..")
            .should().dependOnClassesThat()
            .resideInAPackage("de.littletrotter.domain..")
            .because(
                "de.littletrotter.shared is the Published Language between kotlin-invoice and " +
                "kotlin-xpdf; importing kotlin-invoice's domain package would couple the two " +
                "modules' rollouts instead of keeping the wire contract independent"
            )
            .check(classes)
    }

    /**
     * [de.littletrotter.shared.b2b.codes.B2bCodeListValuesEn16931] and
     * [de.littletrotter.shared.b2b.codes.B2bCodeListValuesExtended] are generated, `internal`
     * (module-wide, not package-scoped) raw value holders. `internal` alone does not stop other
     * kotlin-shared *main* classes from reaching around
     * [de.littletrotter.shared.b2b.codes.B2bCodeList] — this rule turns "go through B2bCodeList"
     * into a gate. [classes] excludes test sources (`ImportOption.DoNotIncludeTests()` above), so
     * the drift test's own necessary direct access to compare parsed XML against the generated
     * constants is unaffected — it never enters this check to begin with.
     */
    @Test
    fun `only B2bCodeList may reference the generated code-list value objects`() {
        noClasses()
            .that().resideOutsideOfPackage("de.littletrotter.shared.b2b.codes..")
            .should().dependOnClassesThat()
            .haveNameMatching(".*\\.B2bCodeListValues(En16931|Extended)")
            .because(
                "B2bCodeList is the single accessor for the generated Factur-X code-list " +
                "values (profile-switched, lazily classloaded EXTENDED)"
            )
            .check(classes)
    }
}
