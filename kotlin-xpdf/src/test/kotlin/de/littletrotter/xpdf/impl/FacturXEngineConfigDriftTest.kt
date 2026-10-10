package de.littletrotter.xpdf.impl

import de.littletrotter.facturx.HttpFacturXEngineClient
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * XPR-3 — split out of kotlin-facturx's `HttpFacturXEngineClientPrecheckTest` (see that class's
 * own comment on the split): `application.properties` is this module's (kotlin-xpdf's) own
 * Quarkus runtime config, not kotlin-facturx's — kotlin-facturx is a plain library jar with no
 * application.properties of its own, so a test asserting that file's content on the classpath can
 * only run from here, where it is actually present. Everything else about
 * `HttpFacturXEngineClient`'s own pre-check behavior stayed in kotlin-facturx with the rest of
 * that test class; this is purely the one assertion that ties the class's own
 * `@ConfigProperty` default back to the config file this module ships.
 */
class FacturXEngineConfigDriftTest {

    @Test
    fun `application-properties configures the same 25 MB default kotlin-xpdf actually runs with`() {
        val text = HttpFacturXEngineClient::class.java.classLoader
            .getResourceAsStream("application.properties")!!
            .bufferedReader().readText()
        val line = text.lines().single { it.startsWith("facturx.engine.max-upload-mb=") }
        assertTrue(
            line.contains("FACTURX_ENGINE_MAX_UPLOAD_MB:25"),
            "expected the FACTURX_ENGINE_MAX_UPLOAD_MB default to be 25 in application.properties, found: $line",
        )
    }
}
