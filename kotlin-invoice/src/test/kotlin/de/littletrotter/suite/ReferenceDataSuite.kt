package de.littletrotter.suite

import org.junit.platform.suite.api.SelectPackages
import org.junit.platform.suite.api.Suite
import org.junit.platform.suite.api.ExcludeClassNamePatterns

/**
 * JUnit Platform Suite — Reference data domain unit tests.
 *
 * Bundles all unit tests for reference data (tax rates, daily allowances,
 * mileage allowances, hotel costs, payment terms as reference).
 *
 * Run:
 *   mvn test -Dtest=ReferenceDataSuite
 */
@Suite
@SelectPackages(
    "de.littletrotter.domain.reference",
    "de.littletrotter.application.reference",
)
@ExcludeClassNamePatterns(".*IT$", ".*IT\\$.*")
class ReferenceDataSuite
