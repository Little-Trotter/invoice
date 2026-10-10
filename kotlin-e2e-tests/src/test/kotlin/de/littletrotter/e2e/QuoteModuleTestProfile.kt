package de.littletrotter.e2e

import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Marks the tests that run against the real offer module and switches on the invoicing side of the
 * master data replication. A distinct profile makes Quarkus restart an application that an earlier
 * test class booted without the module, so the module is always up before the application that
 * publishes to it.
 */
class QuoteModuleTestProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf("quote.enabled" to "true")
}
