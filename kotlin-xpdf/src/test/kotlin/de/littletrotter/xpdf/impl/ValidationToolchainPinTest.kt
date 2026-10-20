package de.littletrotter.xpdf.impl

import de.littletrotter.shared.ValidationToolchain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Properties

/**
 * Pins [ValidationToolchain]'s numbers against their real sources, so the versions the UI's
 * provenance hint names can never silently drift from what actually runs:
 *
 * - the KoSIT validator version against the resolved `org.kosit:validator` jar's own
 *   `pom.properties` on this module's classpath (fails on any dependency bump that forgets the
 *   shared constant),
 * - the XRechnung configuration version/release against the actual scenario ZIP resource
 *   ([EmbeddedKositValidator.SCENARIO_ZIP] is *built from* the shared constants, so a renamed
 *   resource with stale constants makes this lookup fail),
 * - the veraPDF default against the image tag of the `verapdf` service in the compose file (fails
 *   if the service is bumped without the shared constant).
 */
class ValidationToolchainPinTest {

    @Test
    fun `KoSIT validator constant matches the resolved dependency's own pom properties`() {
        val stream = javaClass.classLoader
            .getResourceAsStream("META-INF/maven/org.kosit/validator/pom.properties")
        assertNotNull(stream, "org.kosit:validator pom.properties not found on the classpath")
        val props = Properties().apply { stream!!.use { load(it) } }
        assertEquals(
            ValidationToolchain.KOSIT_VALIDATOR_VERSION,
            props.getProperty("version"),
            "ValidationToolchain.KOSIT_VALIDATOR_VERSION is stale — update it to the resolved org.kosit:validator version",
        )
    }

    @Test
    fun `XRechnung scenario ZIP exists at the path built from the shared constants`() {
        assertNotNull(
            javaClass.classLoader.getResource(EmbeddedKositValidator.SCENARIO_ZIP),
            "scenario resource '${EmbeddedKositValidator.SCENARIO_ZIP}' missing — " +
                "ValidationToolchain's XRECHNUNG_VERSION/XRECHNUNG_CONFIG_RELEASE no longer match the bundled configuration",
        )
    }

    @Test
    fun `veraPDF service image tag is the shared constant, not a local copy`() {
        var dir = java.io.File(System.getProperty("user.dir")).absoluteFile
        while (!java.io.File(dir, "docker-compose.yml").isFile) dir = dir.parentFile
        val compose = java.io.File(dir, "docker-compose.yml").readText()
        assertTrue(
            compose.contains("verapdf/rest:\${LT_VERAPDF_IMAGE_TAG:-v${ValidationToolchain.VERAPDF_DEFAULT_VERSION}}"),
            "the verapdf service must run the version the findings name",
        )
    }
}
