package de.littletrotter.infrastructure.messaging.masterdata

import io.quarkus.test.junit.QuarkusTestProfile

/** Switches the offer module on, which starts the master data event publication. */
class QuoteEnabledProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf("quote.enabled" to "true")
}
