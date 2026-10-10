package de.littletrotter.e2e

import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Marks the tests that run the real offer module and the real mail module together: the
 * invoicing side of the offer integration is switched on, and the application boots after both
 * modules are up. A distinct profile makes Quarkus restart an application that an earlier test
 * class booted without them.
 */
class QuoteMailModuleTestProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf("quote.enabled" to "true")
}
