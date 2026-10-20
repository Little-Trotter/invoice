package de.littletrotter.suite

import org.junit.platform.suite.api.SelectPackages
import org.junit.platform.suite.api.Suite
import org.junit.platform.suite.api.ExcludeClassNamePatterns

/**
 * JUnit Platform Suite — Invoice domain unit tests.
 *
 * Bundles all unit tests for the Invoice aggregate (domain + application layer).
 * Contains no integration tests (Quarkus/Testcontainer) — these run separately via Maven Failsafe.
 *
 * Run:
 *   mvn test -Dtest=InvoiceSuite
 */
@Suite
@SelectPackages(
    "de.littletrotter.domain.invoice",
    "de.littletrotter.application.invoice",
)
@ExcludeClassNamePatterns(".*IT$", ".*IT\\$.*")
class InvoiceSuite
