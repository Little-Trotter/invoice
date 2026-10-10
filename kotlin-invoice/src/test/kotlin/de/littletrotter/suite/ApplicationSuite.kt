package de.littletrotter.suite

import org.junit.platform.suite.api.SelectPackages
import org.junit.platform.suite.api.Suite
import org.junit.platform.suite.api.ExcludeClassNamePatterns

/**
 * JUnit Platform Suite — Application-Layer Unit-Tests (domainübergreifend).
 *
 * Bündelt alle Unit-Tests des Application-Layers: Services, Facades, DTOs,
 * Error-Handling — ohne domain-spezifische Suites zu duplizieren.
 *
 * Ausführen:
 *   mvn test -Dtest=ApplicationSuite
 */
@Suite
@SelectPackages(
    "de.littletrotter.application",
    "de.littletrotter.application.dto",
    "de.littletrotter.application.error",
)
@ExcludeClassNamePatterns(".*IT$", ".*IT\\$.*")
class ApplicationSuite
