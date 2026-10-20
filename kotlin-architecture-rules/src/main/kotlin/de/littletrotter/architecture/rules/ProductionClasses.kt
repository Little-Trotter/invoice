package de.littletrotter.architecture.rules

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.core.importer.Location

/**
 * Imports the production classes a module's gate checks.
 *
 * ArchUnit's own test filter only knows test output directories. Once Maven has packaged a
 * module, a sibling's test fixtures reach the classpath as its `-tests.jar` (a test-jar
 * dependency), and their deliberately fixed clocks would count as production code.
 */
object ProductionClasses {

    /** Leaves out classes packaged in a Maven test-jar. */
    val doNotIncludeTestJars = ImportOption { location: Location -> !location.contains("-tests.jar") }

    fun of(vararg packages: String): JavaClasses = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .withImportOption(doNotIncludeTestJars)
        .importPackages(*packages)
}
