package de.littletrotter.suite

import org.junit.platform.suite.api.SelectPackages
import org.junit.platform.suite.api.Suite
import org.junit.platform.suite.api.ExcludeClassNamePatterns

/**
 * JUnit Platform Suite — Masterdata domain unit tests.
 *
 * Bundles all unit tests for masterdata (recipients, senders, addresses, documents, items,
 * payment terms) including associated application services.
 *
 * Run:
 *   mvn test -Dtest=MasterdataSuite
 */
@Suite
@SelectPackages(
    "de.littletrotter.domain.masterdata",
    "de.littletrotter.application.masterdata",
)
@ExcludeClassNamePatterns(".*IT$", ".*IT\\$.*")
class MasterdataSuite
